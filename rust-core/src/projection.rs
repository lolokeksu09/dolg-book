//! Read-only balance and FIFO projection for validated, insertion-ordered records.

use std::collections::HashSet;

#[derive(Clone, Copy, Debug)]
pub struct LedgerRecord<'a> {
    pub id: &'a str,
    pub client: &'a str,
    pub kind: &'a str,
    pub amount: i64,
    pub due: &'a str,
    pub reverses: &'a str,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Projection {
    pub balance: i64,
    pub overdue: i64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ProjectionError {
    Overflow,
}

/// Matches the existing Android LedgerStore projection, including ISO-string
/// due-date comparison and allocation by insertion order, not due-date order.
/// Callers must supply records already validated by the storage boundary.
pub fn project(
    rows: &[LedgerRecord<'_>],
    client: &str,
    today_iso: &str,
) -> Result<Projection, ProjectionError> {
    use ProjectionError::Overflow;
    let reversed: HashSet<&str> = rows
        .iter()
        .filter(|row| row.kind == "reversal")
        .map(|row| row.reverses)
        .collect();

    let mut balance = 0_i64;
    let mut paid = 0_i64;
    for row in rows {
        if row.client != client || row.kind == "reversal" || reversed.contains(row.id) {
            continue;
        }
        balance = if row.kind == "debt" {
            balance.checked_add(row.amount)
        } else {
            balance.checked_sub(row.amount)
        }
        .ok_or(Overflow)?;
        if row.kind == "payment" {
            paid = paid.checked_add(row.amount).ok_or(Overflow)?;
        }
    }

    let mut overdue = 0_i64;
    for row in rows {
        if row.client != client || row.kind != "debt" || reversed.contains(row.id) {
            continue;
        }
        let applied = paid.min(row.amount);
        paid = paid.checked_sub(applied).ok_or(Overflow)?;
        if !row.due.is_empty() && row.due < today_iso {
            let unpaid = row.amount.checked_sub(applied).ok_or(Overflow)?;
            overdue = overdue.checked_add(unpaid).ok_or(Overflow)?;
        }
    }
    Ok(Projection { balance, overdue })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn verify_case(header: &[&str], rows: &[LedgerRecord<'_>]) {
        let actual = project(rows, header[2], header[3]).unwrap();
        let expected = Projection {
            balance: header[4].parse().unwrap(),
            overdue: header[5].parse().unwrap(),
        };
        assert_eq!(actual, expected, "{}", header[1]);
    }

    #[test]
    fn shared_projection_contract() {
        let mut header = Vec::new();
        let mut rows = Vec::new();
        let mut count = 0;
        for line in include_str!("../../tests/contracts/projection.tsv").lines() {
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let fields: Vec<_> = line.split('|').collect();
            match fields[0] {
                "case" => {
                    if !header.is_empty() {
                        verify_case(&header, &rows);
                        count += 1;
                    }
                    assert_eq!(fields.len(), 6);
                    header = fields;
                    rows.clear();
                }
                "row" => {
                    assert_eq!(fields.len(), 7);
                    assert!(!header.is_empty());
                    rows.push(LedgerRecord {
                        id: fields[1],
                        client: fields[2],
                        kind: fields[3],
                        amount: fields[4].parse().unwrap(),
                        due: fields[5],
                        reverses: fields[6],
                    });
                }
                _ => panic!("Unexpected fixture: {line}"),
            }
        }
        assert!(!header.is_empty());
        verify_case(&header, &rows);
        assert_eq!(count + 1, 10);
    }

    #[test]
    fn arithmetic_overflow_is_an_error() {
        let row = LedgerRecord {
            id: "a",
            client: "client",
            kind: "debt",
            amount: i64::MAX,
            due: "2026-11-20",
            reverses: "",
        };
        let rows = [
            row,
            LedgerRecord {
                id: "b",
                amount: 1,
                ..row
            },
        ];
        assert_eq!(
            project(&rows, "client", "2026-11-24"),
            Err(ProjectionError::Overflow)
        );
    }
}
