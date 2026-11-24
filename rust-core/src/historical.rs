//! Historical receipt guard over validated records and platform-local epoch days.

use crate::projection::{LedgerRecord, project};
use std::collections::{BTreeMap, HashMap};

#[derive(Clone, Copy, Debug)]
pub struct DatedRecord<'a> {
    pub entry: LedgerRecord<'a>,
    pub day: i64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum HistoricalError {
    Overflow,
    BrokenReversal,
    UnknownOperation,
}

/// Earliest subsequent end-of-day minimum. Local date conversion belongs to
/// the platform adapter; insertion order is retained for reversal lookup.
pub fn maximum(
    rows: &[DatedRecord<'_>],
    client: &str,
    selected: Option<i64>,
    today: i64,
) -> Result<i64, HistoricalError> {
    use HistoricalError::*;
    let Some(day) = selected.filter(|day| *day <= today) else {
        return Ok(0);
    };
    let entries: Vec<_> = rows.iter().map(|row| row.entry).collect();
    let current = project(&entries, client, "")
        .map_err(|_| Overflow)?
        .balance
        .max(0);
    if day == today {
        return Ok(current);
    }
    let mut changes = BTreeMap::<i64, i64>::new();
    let mut earlier = HashMap::<&str, &str>::new();
    for row in rows {
        let entry = row.entry;
        if entry.client != client {
            continue;
        }
        let delta = match entry.kind {
            "debt" => entry.amount,
            "payment" => entry.amount.checked_neg().ok_or(Overflow)?,
            "reversal" => {
                let kind = earlier.get(entry.reverses).ok_or(BrokenReversal)?;
                if *kind == "debt" {
                    entry.amount.checked_neg().ok_or(Overflow)?
                } else {
                    entry.amount
                }
            }
            _ => return Err(UnknownOperation),
        };
        let value = changes.entry(row.day).or_default();
        *value = value.checked_add(delta).ok_or(Overflow)?;
        earlier.insert(entry.id, entry.kind);
    }
    changes.entry(day).or_default();
    let mut running = 0_i64;
    let mut minimum = current;
    for (date, delta) in changes {
        if date > today {
            break;
        }
        running = running.checked_add(delta).ok_or(Overflow)?;
        if date >= day {
            minimum = minimum.min(running);
        }
    }
    Ok(minimum.max(0))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn verify(header: &[&str], rows: &[DatedRecord<'_>]) {
        let selected = if header[3].is_empty() {
            None
        } else {
            Some(header[3].parse().unwrap())
        };
        let actual = maximum(rows, header[2], selected, header[4].parse().unwrap());
        let expected = if header[5] == "error" {
            Err(HistoricalError::BrokenReversal)
        } else {
            Ok(header[5].parse().unwrap())
        };
        assert_eq!(actual, expected, "{}", header[1]);
    }

    #[test]
    fn historical_contract() {
        let mut header = Vec::new();
        let mut rows = Vec::new();
        let mut count = 0;
        for line in include_str!("../../tests/contracts/dated-epoch.tsv").lines() {
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let fields: Vec<_> = line.split('|').collect();
            match fields[0] {
                "case" => {
                    if !header.is_empty() {
                        verify(&header, &rows);
                        count += 1;
                    }
                    assert_eq!(fields.len(), 6);
                    header = fields;
                    rows.clear();
                }
                "row" => {
                    assert_eq!(fields.len(), 7);
                    rows.push(DatedRecord {
                        entry: LedgerRecord {
                            id: fields[1],
                            client: fields[2],
                            kind: fields[3],
                            amount: fields[4].parse().unwrap(),
                            due: "",
                            reverses: fields[6],
                        },
                        day: fields[5].parse().unwrap(),
                    });
                }
                _ => panic!("Unexpected fixture: {line}"),
            }
        }
        assert!(!header.is_empty());
        verify(&header, &rows);
        assert_eq!(count + 1, 14);
    }
}
