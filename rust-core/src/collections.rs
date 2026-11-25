//! Collection analytics over validated records and platform-local due dates.

use crate::projection::{LedgerRecord, ProjectionError};
use std::collections::{HashMap, HashSet};

#[derive(Clone, Copy, Debug)]
pub struct CollectionRecord<'a> {
    pub entry: LedgerRecord<'a>,
    pub due_day: Option<i64>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub struct Status {
    pub balance: i64,
    pub overdue: i64,
    pub upcoming: i64,
    pub next_due: Option<i64>,
    pub last_operation_index: usize,
}

/// One grouped projection for every client represented in the input.
/// Date conversion and validation belong to the platform adapter.
pub fn analyze<'a>(
    rows: &[CollectionRecord<'a>],
    today: i64,
) -> Result<HashMap<&'a str, Status>, ProjectionError> {
    use ProjectionError::Overflow;
    let window_end = today.checked_add(7).ok_or(Overflow)?;
    let reversed: HashSet<_> = rows
        .iter()
        .filter(|row| row.entry.kind == "reversal")
        .map(|row| row.entry.reverses)
        .collect();
    let mut result = HashMap::<&str, Status>::new();
    let mut payments = HashMap::<&str, i64>::new();

    for (index, row) in rows.iter().enumerate() {
        let entry = row.entry;
        result.entry(entry.client).or_default().last_operation_index = index;
        if entry.kind == "payment" && !reversed.contains(entry.id) {
            let total = payments.entry(entry.client).or_default();
            *total = total.checked_add(entry.amount).ok_or(Overflow)?;
        }
    }

    for row in rows {
        let entry = row.entry;
        if entry.kind != "debt" || reversed.contains(entry.id) {
            continue;
        }
        let paid = payments.entry(entry.client).or_default();
        let applied = (*paid).min(entry.amount);
        *paid = paid.checked_sub(applied).ok_or(Overflow)?;
        let unpaid = entry.amount.checked_sub(applied).ok_or(Overflow)?;
        let status = result.entry(entry.client).or_default();
        status.balance = status.balance.checked_add(unpaid).ok_or(Overflow)?;
        if unpaid == 0 {
            continue;
        }
        if let Some(due) = row.due_day {
            if due < today {
                status.overdue = status.overdue.checked_add(unpaid).ok_or(Overflow)?;
            } else if due <= window_end {
                status.upcoming = status.upcoming.checked_add(unpaid).ok_or(Overflow)?;
                status.next_due = Some(status.next_due.map_or(due, |old| old.min(due)));
            }
        }
    }
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn optional_day(value: &str) -> Option<i64> {
        if value.is_empty() {
            None
        } else {
            Some(value.parse().unwrap())
        }
    }

    #[test]
    fn shared_collection_contract() {
        let mut today = None;
        let mut rows = Vec::new();
        let mut expected = HashMap::new();
        for line in include_str!("../../tests/contracts/collections-epoch.tsv").lines() {
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let fields: Vec<_> = line.split('|').collect();
            match fields[0] {
                "today" => today = Some(fields[1].parse().unwrap()),
                "row" => rows.push(CollectionRecord {
                    entry: LedgerRecord {
                        id: fields[1],
                        client: fields[2],
                        kind: fields[3],
                        amount: fields[4].parse().unwrap(),
                        due: "",
                        reverses: fields[6],
                    },
                    due_day: optional_day(fields[5]),
                }),
                "expected" => {
                    expected.insert(
                        fields[1],
                        Status {
                            balance: fields[2].parse().unwrap(),
                            overdue: fields[3].parse().unwrap(),
                            upcoming: fields[4].parse().unwrap(),
                            next_due: optional_day(fields[5]),
                            last_operation_index: fields[6].parse().unwrap(),
                        },
                    );
                }
                _ => panic!("Unexpected fixture: {line}"),
            }
        }
        assert_eq!(expected.len(), 3);
        let today = today.unwrap();
        assert_eq!(analyze(&rows, today).unwrap(), expected);
        assert!(analyze(&[], today).unwrap().is_empty());
        assert_eq!(analyze(&[], i64::MAX), Err(ProjectionError::Overflow));
    }
}
