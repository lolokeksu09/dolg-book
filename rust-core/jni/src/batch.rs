//! Bounded primitive-array JNI transport. No strings, names or phone numbers cross JNI.
use raschet_ledger::collections::{CollectionRecord, analyze};
use raschet_ledger::historical::{DatedRecord, maximum};
use raschet_ledger::projection::{LedgerRecord, project};
use raschet_ledger::report::{Totals, build};
use std::ffi::c_void;
use std::panic::catch_unwind;

const WIDTH: usize = 7;
const MAX_ROWS: usize = 100_000;
const NONE: i64 = i64::MIN;
const INVALID: i32 = -1;
const CALCULATION: i32 = -2;
const TRANSPORT: i32 = -3;
const INTERNAL: i32 = -4;

struct Row {
    id: String,
    owner: String,
    kind: &'static str,
    amount: i64,
    due: i64,
    reverses: String,
    day: i64,
}

impl Row {
    fn record(&self) -> LedgerRecord<'_> {
        LedgerRecord {
            id: &self.id,
            client: &self.owner,
            kind: self.kind,
            amount: self.amount,
            due: if self.due == 1 { "1" } else { "" },
            reverses: &self.reverses,
        }
    }

    fn dated(&self) -> DatedRecord<'_> {
        DatedRecord {
            entry: self.record(),
            day: self.day,
        }
    }
}

fn totals(value: &Totals) -> [i64; 5] {
    [
        value.debts,
        value.payments,
        value.debt_reversals,
        value.payment_reversals,
        value.count as i64,
    ]
}

fn compute(
    mode: i32,
    input: &[i64],
    owners: usize,
    a: i64,
    b: i64,
    c: i64,
) -> Result<Vec<i64>, i32> {
    if input.len() % WIDTH != 0 || input.len() / WIDTH > MAX_ROWS {
        return Err(INVALID);
    }
    if owners > input.len() / WIDTH {
        return Err(INVALID);
    }
    let mut rows = Vec::with_capacity(input.len() / WIDTH);
    for values in input.chunks_exact(WIDTH) {
        let kind = match values[2] {
            1 => "debt",
            2 => "payment",
            3 => "reversal",
            _ => return Err(INVALID),
        };
        if values[0] <= 0
            || values[1] < 0
            || values[1] >= owners as i64
            || !(1..=raschet_ledger::MAX_AMOUNT).contains(&values[3])
            || values[5] < 0
        {
            return Err(INVALID);
        }
        rows.push(Row {
            id: values[0].to_string(),
            owner: values[1].to_string(),
            kind,
            amount: values[3],
            due: values[4],
            reverses: values[5].to_string(),
            day: values[6],
        });
    }
    match mode {
        1 => {
            if a < -1 || a >= owners as i64 {
                return Err(INVALID);
            }
            let entries: Vec<_> = rows.iter().map(Row::record).collect();
            let result = project(&entries, &a.to_string(), "2").map_err(|_| CALCULATION)?;
            Ok(vec![result.balance, result.overdue])
        }
        2 => {
            if a < -1 || a >= owners as i64 {
                return Err(INVALID);
            }
            let entries: Vec<_> = rows.iter().map(Row::dated).collect();
            let result = maximum(&entries, &a.to_string(), Some(b), c).map_err(|_| CALCULATION)?;
            Ok(vec![result])
        }
        3 => {
            let entries: Vec<_> = rows
                .iter()
                .map(|row| CollectionRecord {
                    entry: row.record(),
                    due_day: (row.due != NONE).then_some(row.due),
                })
                .collect();
            let result = analyze(&entries, a).map_err(|_| CALCULATION)?;
            if result.len() != owners {
                return Err(INVALID);
            }
            let mut output = vec![0; owners * 5];
            for (client, value) in result {
                let index = client.parse::<usize>().map_err(|_| INTERNAL)? * 5;
                output[index..index + 5].copy_from_slice(&[
                    value.balance,
                    value.overdue,
                    value.upcoming,
                    value.next_due.unwrap_or(NONE),
                    value.last_operation_index as i64,
                ]);
            }
            Ok(output)
        }
        4 => {
            let days = b.checked_sub(a).filter(|days| (0..=365).contains(days));
            let days = days.ok_or(INVALID)? as usize + 1;
            let entries: Vec<_> = rows.iter().map(Row::dated).collect();
            let report = build(&entries, a, b).map_err(|_| CALCULATION)?;
            let mut output = vec![0; 8 + days * 5];
            output[0] = report.opening;
            output[1] = report.closing;
            output[2..7].copy_from_slice(&totals(&report.totals));
            output[7] = report.active_customers as i64;
            for (day, value) in report.daily {
                let index = 8 + (day - a) as usize * 5;
                output[index..index + 5].copy_from_slice(&totals(&value));
            }
            Ok(output)
        }
        _ => Err(INVALID),
    }
}

// JNI 1.6 function table, indices verified against jni.h in host CI.
// https://docs.oracle.com/en/java/javase/17/docs/specs/jni/functions.html
type Env = *mut *const *const c_void;
type Array = *mut c_void;
type Length = unsafe extern "system" fn(Env, Array) -> i32;
type Read = unsafe extern "system" fn(Env, Array, i32, i32, *mut i64);
type Write = unsafe extern "system" fn(Env, Array, i32, i32, *const i64);
type Exception = unsafe extern "system" fn(Env) -> u8;

const GET_ARRAY_LENGTH: usize = 171;
const GET_LONG_ARRAY_REGION: usize = 204;
const SET_LONG_ARRAY_REGION: usize = 212;
const EXCEPTION_CHECK: usize = 228;

unsafe fn slot(env: Env, index: usize) -> *const c_void {
    // SAFETY: the JVM supplies a valid JNIEnv for this native invocation;
    // these four slots are fixed by the JNI specification.
    unsafe { *(*env).add(index) }
}

unsafe fn transport(
    env: Env,
    mode: i32,
    input: Array,
    owners: i32,
    parameters: [i64; 3],
    output: Array,
) -> i32 {
    let [a, b, c] = parameters;
    if env.is_null() || input.is_null() || output.is_null() || owners < 0 {
        return INVALID;
    }
    // SAFETY: typed signatures match the fixed JNI table. Region operations
    // copy into owned Rust storage, and no JVM array remains pinned.
    unsafe {
        let length = std::mem::transmute::<*const c_void, Length>(slot(env, GET_ARRAY_LENGTH));
        let read = std::mem::transmute::<*const c_void, Read>(slot(env, GET_LONG_ARRAY_REGION));
        let write = std::mem::transmute::<*const c_void, Write>(slot(env, SET_LONG_ARRAY_REGION));
        let exception = std::mem::transmute::<*const c_void, Exception>(slot(env, EXCEPTION_CHECK));
        let size = length(env, input);
        if exception(env) != 0 {
            return TRANSPORT;
        }
        let output_size = length(env, output);
        if exception(env) != 0 {
            return TRANSPORT;
        }
        if size < 0 || size as usize > MAX_ROWS * WIDTH || output_size < 0 {
            return INVALID;
        }
        let mut values = vec![0_i64; size as usize];
        if size > 0 {
            read(env, input, 0, size, values.as_mut_ptr());
            if exception(env) != 0 {
                return TRANSPORT;
            }
        }
        let result = match compute(mode, &values, owners as usize, a, b, c) {
            Ok(result) => result,
            Err(error) => return error,
        };
        if result.len() != output_size as usize {
            return INVALID;
        }
        if output_size > 0 {
            write(env, output, 0, output_size, result.as_ptr());
            if exception(env) != 0 {
                return TRANSPORT;
            }
        }
        0
    }
}

/// JNI descriptor: (I[JIJJJ[J)I.
///
/// # Safety
/// Called only by the JVM with live local references to long arrays and its JNIEnv.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_ventry_raschet_bridge_NativeLedger_compute(
    env: Env,
    _class: *mut c_void,
    mode: i32,
    input: Array,
    owners: i32,
    a: i64,
    b: i64,
    c: i64,
    output: Array,
) -> i32 {
    // SAFETY: all references are scoped to this native invocation.
    let result = catch_unwind(|| unsafe { transport(env, mode, input, owners, [a, b, c], output) });
    result.unwrap_or(INTERNAL)
}
