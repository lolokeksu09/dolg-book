//! Period reporting over platform-local epoch days; reversal lookup follows insertion order.

use crate::historical::DatedRecord;
use crate::projection::LedgerRecord;
use std::collections::{BTreeMap, HashMap, HashSet};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ReportError {
    Range,
    Overflow,
    BrokenReversal,
    InvalidReversalKind,
    UnknownOperation,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Totals {
    pub debts: i64,
    pub payments: i64,
    pub debt_reversals: i64,
    pub payment_reversals: i64,
    pub count: usize,
}

#[derive(Clone, Copy)]
enum Kind {
    Debt,
    Payment,
    DebtReversal,
    PaymentReversal,
}

impl Totals {
    pub fn net(&self) -> Result<i64, ReportError> {
        self.debts
            .checked_sub(self.payments)
            .and_then(|value| value.checked_sub(self.debt_reversals))
            .and_then(|value| value.checked_add(self.payment_reversals))
            .ok_or(ReportError::Overflow)
    }

    fn add(&mut self, kind: Kind, amount: i64) -> Result<(), ReportError> {
        let total = match kind {
            Kind::Debt => &mut self.debts,
            Kind::Payment => &mut self.payments,
            Kind::DebtReversal => &mut self.debt_reversals,
            Kind::PaymentReversal => &mut self.payment_reversals,
        };
        *total = total.checked_add(amount).ok_or(ReportError::Overflow)?;
        self.count = self.count.checked_add(1).ok_or(ReportError::Overflow)?;
        Ok(())
    }
}

#[derive(Debug, PartialEq, Eq)]
pub struct Report {
    pub opening: i64,
    pub closing: i64,
    pub totals: Totals,
    pub daily: BTreeMap<i64, Totals>,
    pub active_customers: usize,
}

fn kind(
    entry: LedgerRecord<'_>,
    earlier: &HashMap<&str, LedgerRecord<'_>>,
) -> Result<Kind, ReportError> {
    match entry.kind {
        "debt" => Ok(Kind::Debt),
        "payment" => Ok(Kind::Payment),
        "reversal" => {
            let original = earlier
                .get(entry.reverses)
                .filter(|row| row.client == entry.client && row.amount == entry.amount)
                .ok_or(ReportError::BrokenReversal)?;
            match original.kind {
                "debt" => Ok(Kind::DebtReversal),
                "payment" => Ok(Kind::PaymentReversal),
                _ => Err(ReportError::InvalidReversalKind),
            }
        }
        _ => Err(ReportError::UnknownOperation),
    }
}

/// The caller validates record shape and normalizes dates. Future rows are not
/// included in aggregates but are retained for insertion-order reference lookup.
pub fn build(rows: &[DatedRecord<'_>], from: i64, to: i64) -> Result<Report, ReportError> {
    if !matches!(to.checked_sub(from), Some(0..=365)) {
        return Err(ReportError::Range);
    }
    let mut earlier = HashMap::new();
    let mut before = Totals::default();
    let mut totals = Totals::default();
    let mut daily = BTreeMap::<i64, Totals>::new();
    let mut customers = HashSet::new();
    for row in rows {
        let entry = row.entry;
        if row.day < from {
            before.add(kind(entry, &earlier)?, entry.amount)?;
        } else if row.day <= to {
            let operation = kind(entry, &earlier)?;
            totals.add(operation, entry.amount)?;
            daily
                .entry(row.day)
                .or_default()
                .add(operation, entry.amount)?;
            customers.insert(entry.client);
        }
        earlier.insert(entry.id, entry);
    }
    let opening = before.net()?;
    let closing = opening
        .checked_add(totals.net()?)
        .ok_or(ReportError::Overflow)?;
    Ok(Report {
        opening,
        closing,
        totals,
        daily,
        active_customers: customers.len(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn totals(fields: &[&str]) -> Totals {
        Totals {
            debts: fields[0].parse().unwrap(),
            payments: fields[1].parse().unwrap(),
            debt_reversals: fields[2].parse().unwrap(),
            payment_reversals: fields[3].parse().unwrap(),
            count: fields[4].parse().unwrap(),
        }
    }

    fn verify(header: &[&str], rows: &[DatedRecord<'_>], daily: &BTreeMap<i64, Totals>) {
        let report = build(rows, header[2].parse().unwrap(), header[3].parse().unwrap()).unwrap();
        assert_eq!(report.opening, header[4].parse::<i64>().unwrap());
        assert_eq!(report.closing, header[5].parse::<i64>().unwrap());
        assert_eq!(report.totals, totals(&header[6..11]));
        assert_eq!(
            report.active_customers,
            header[11].parse::<usize>().unwrap()
        );
        assert_eq!(&report.daily, daily, "{}", header[1]);
    }

    #[test]
    fn shared_report_fixture() {
        let mut header = Vec::new();
        let mut rows = Vec::new();
        let mut daily = BTreeMap::new();
        let mut count = 0;
        for line in include_str!("../../tests/contracts/report-epoch.tsv").lines() {
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let fields: Vec<_> = line.split('|').collect();
            match fields[0] {
                "case" => {
                    if !header.is_empty() {
                        verify(&header, &rows, &daily);
                        count += 1;
                    }
                    assert_eq!(fields.len(), 12);
                    header = fields;
                    rows.clear();
                    daily.clear();
                }
                "row" => rows.push(DatedRecord {
                    entry: LedgerRecord {
                        id: fields[1],
                        client: fields[2],
                        kind: fields[3],
                        amount: fields[4].parse().unwrap(),
                        due: "",
                        reverses: fields[6],
                    },
                    day: fields[5].parse().unwrap(),
                }),
                "daily" => {
                    daily.insert(fields[1].parse().unwrap(), totals(&fields[2..]));
                }
                _ => panic!("Unexpected fixture: {line}"),
            }
        }
        verify(&header, &rows, &daily);
        assert_eq!(count + 1, 5);
    }

    #[test]
    fn rejects_invalid_ranges_and_reversal_links() {
        assert_eq!(build(&[], 10, 9), Err(ReportError::Range));
        assert_eq!(build(&[], 0, 366), Err(ReportError::Range));
        assert_eq!(build(&[], i64::MIN, i64::MAX), Err(ReportError::Range));
        let debt = LedgerRecord {
            id: "1",
            client: "a",
            kind: "debt",
            amount: 100,
            due: "",
            reverses: "",
        };
        let reversal = LedgerRecord {
            id: "2",
            kind: "reversal",
            reverses: "1",
            ..debt
        };
        let row = |entry| DatedRecord { entry, day: 1 };
        assert_eq!(
            build(&[row(reversal)], 1, 1),
            Err(ReportError::BrokenReversal)
        );
        assert_eq!(
            build(
                &[
                    row(debt),
                    row(LedgerRecord {
                        client: "b",
                        ..reversal
                    })
                ],
                1,
                1
            ),
            Err(ReportError::BrokenReversal)
        );
        assert_eq!(
            build(
                &[
                    row(debt),
                    row(LedgerRecord {
                        amount: 99,
                        ..reversal
                    })
                ],
                1,
                1
            ),
            Err(ReportError::BrokenReversal)
        );
        assert_eq!(
            build(
                &[
                    row(debt),
                    row(reversal),
                    row(LedgerRecord {
                        id: "3",
                        reverses: "2",
                        ..reversal
                    })
                ],
                1,
                1
            ),
            Err(ReportError::InvalidReversalKind)
        );
        assert_eq!(
            build(
                &[row(LedgerRecord {
                    kind: "other",
                    ..debt
                })],
                1,
                1
            ),
            Err(ReportError::UnknownOperation)
        );
    }
}
