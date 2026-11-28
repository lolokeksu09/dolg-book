//! JNI boundary for the scalar correction contract.
//! Primitive JNI arguments only; no dereferencing VM pointers or shared state.

mod batch;

use raschet_ledger::{CorrectionError, corrected_balance};
use std::ffi::c_void;
use std::panic::catch_unwind;

const INVALID: i64 = -1;
const PAID: i64 = -2;
const RANGE: i64 = -3;
const INTERNAL: i64 = -4;

// Non-negative results are balances in kopecks. Negative results are explicit
// errors, never valid balances. Keep the ABI stable until Android integration.
fn correction(kind: i32, available: i64, old_amount: i64, replacement: i64) -> i64 {
    let operation = match kind {
        1 => "debt",
        2 => "payment",
        _ => return INVALID,
    };
    match corrected_balance(available, operation, old_amount, replacement) {
        Ok(amount) => amount,
        Err(CorrectionError::Invalid) => INVALID,
        Err(CorrectionError::Paid) => PAID,
        Err(CorrectionError::Range) => RANGE,
    }
}

/// JNI static method descriptor: (IJJJ)J.
/// SAFETY: the exported symbol uniquely matches NativeCorrection.correctedBalance;
/// opaque JVM arguments are unused, and only primitive values cross the boundary.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ventry_raschet_bridge_NativeCorrection_correctedBalance(
    _env: *mut c_void,
    _class: *mut c_void,
    kind: i32,
    available: i64,
    old_amount: i64,
    replacement: i64,
) -> i64 {
    catch_unwind(|| correction(kind, available, old_amount, replacement)).unwrap_or(INTERNAL)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn error_codes_never_overlap_balances() {
        assert_eq!(correction(1, 100, 100, 0), 0);
        assert_eq!(correction(0, 100, 100, 0), INVALID);
        assert_eq!(correction(1, 10, 100, 0), PAID);
        assert_eq!(correction(2, 10, 100, 111), RANGE);
    }
}
