#![forbid(unsafe_code)]

//! Pure financial arithmetic. No JNI or database I/O in this crate yet.

pub mod collections;
pub mod historical;
pub mod projection;
pub mod report;

pub const MAX_AMOUNT: i64 = 100_000_000_000;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CorrectionError {
    Invalid,
    Paid,
    Range,
}

pub fn corrected_balance(
    available: i64,
    kind: &str,
    old_amount: i64,
    replacement: i64,
) -> Result<i64, CorrectionError> {
    use CorrectionError::*;
    if !matches!(kind, "debt" | "payment")
        || !(0..=MAX_AMOUNT).contains(&available)
        || !(1..=MAX_AMOUNT).contains(&old_amount)
        || !(0..=MAX_AMOUNT).contains(&replacement)
    {
        return Err(Invalid);
    }
    let without_old = if kind == "debt" {
        available.checked_sub(old_amount)
    } else {
        available.checked_add(old_amount)
    }
    .ok_or(Range)?;
    if without_old < 0 {
        return Err(Paid);
    }
    let updated = if kind == "debt" {
        without_old.checked_add(replacement)
    } else {
        without_old.checked_sub(replacement)
    }
    .ok_or(Range)?;
    if !(0..=MAX_AMOUNT).contains(&updated) {
        return Err(Range);
    }
    Ok(updated)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shared_correction_contract() {
        let mut count = 0;
        for line in include_str!("../../tests/contracts/correction.tsv").lines() {
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let fields: Vec<_> = line.split_whitespace().collect();
            assert_eq!(fields.len(), 5, "{line}");
            let actual = corrected_balance(
                fields[0].parse().unwrap(),
                fields[1],
                fields[2].parse().unwrap(),
                fields[3].parse().unwrap(),
            );
            let expected = match fields[4] {
                "invalid" => Err(CorrectionError::Invalid),
                "paid" => Err(CorrectionError::Paid),
                "range" => Err(CorrectionError::Range),
                value => Ok(value.parse().unwrap()),
            };
            assert_eq!(actual, expected, "{line}");
            count += 1;
        }
        assert!(count >= 18);
    }
}
