use crate::application::ApplicationData;
use anyhow::{Context, Result};
use chrono::{DateTime, SecondsFormat, TimeDelta, Utc};
use indexmap::IndexMap;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Deserializer, Serialize, Serializer};
use std::fmt::Write;
use std::path::Path;

pub type Snowflake = u64;

fn is_zero(id: &Snowflake) -> bool {
    *id == 0
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct BotConfig {
    pub source: String,
    pub sheets: IndexMap<String, String>,
    pub offsets: IndexMap<String, usize>,
    pub refresh: IsoDuration,
    pub countdown: IsoDuration,
    pub server: ServerData,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq)]
#[serde(default)]
pub struct ServerData {
    #[serde(rename = "preview_channel_id", skip_serializing_if = "is_zero")]
    pub preview_channel: Snowflake,
    #[serde(rename = "application_category_id", skip_serializing_if = "is_zero")]
    pub application_category: Snowflake,
    #[serde(rename = "archive_category_id", skip_serializing_if = "is_zero")]
    pub archive_category: Snowflake,
    #[serde(rename = "rejected_category_id", skip_serializing_if = "is_zero")]
    pub rejected_category: Snowflake,
    #[serde(rename = "applicant_role_id", skip_serializing_if = "is_zero")]
    pub applicant_role: Snowflake,
    #[serde(rename = "provisional_role_id", skip_serializing_if = "is_zero")]
    pub provisional_role: Snowflake,
    #[serde(rename = "moderator_role_id", skip_serializing_if = "is_zero")]
    pub moderator_role: Snowflake,
    #[serde(rename = "voice_channel_id", skip_serializing_if = "is_zero")]
    pub voice_channel: Snowflake,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq)]
#[serde(default)]
pub struct RuntimeData {
    #[serde(skip_serializing_if = "IndexMap::is_empty")]
    pub messages: IndexMap<Snowflake, ApplicationReference>,
    #[serde(skip_serializing_if = "IndexMap::is_empty")]
    pub tickets: IndexMap<Snowflake, Ticket>,
    #[serde(skip_serializing_if = "IndexMap::is_empty")]
    pub applications: IndexMap<String, Vec<ApplicationData>>,
}

impl RuntimeData {
    pub fn entry(&self, message: Snowflake) -> Option<&ApplicationData> {
        self.messages.get(&message)?.to_entry(&self.applications)
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct ApplicationReference {
    #[serde(rename = "type")]
    pub kind: String,
    pub index: usize,
}

impl ApplicationReference {
    pub fn to_entry<'a>(&self, map: &'a IndexMap<String, Vec<ApplicationData>>) -> Option<&'a ApplicationData> {
        map.get(&self.kind)?.get(self.index)
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct Ticket {
    pub member: Snowflake,
    pub channel: Snowflake,
    pub state: TicketState,
    #[serde(default, with = "instant", skip_serializing_if = "Option::is_none")]
    pub countdown: Option<DateTime<Utc>>,
}

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "UPPERCASE")]
pub enum TicketState {
    Open,
    Accepted,
    Rejected,
}

pub trait Saveable: Serialize + DeserializeOwned {
    const FILE: &'static str;
    const KIND: &'static str;
    fn empty() -> Self;

    fn to_json(&self) -> Result<String> {
        #[derive(Serialize)]
        struct Tagged<'a, T> {
            #[serde(rename = "type")]
            kind: &'static str,
            #[serde(flatten)]
            inner: &'a T,
        }
        Ok(serde_json::to_string_pretty(&Tagged { kind: Self::KIND, inner: self })?)
    }

    fn save(&self) -> Result<()> {
        std::fs::write(Self::FILE, self.to_json()?).with_context(|| format!("Failed to write {}", Self::FILE))
    }

    fn load() -> Result<Self> {
        if Path::new(Self::FILE).exists() {
            let text = std::fs::read_to_string(Self::FILE)?;
            serde_json::from_str(&text).with_context(|| format!("Failed to parse {}", Self::FILE))
        } else {
            let value = Self::empty();
            value.save()?;
            Ok(value)
        }
    }
}

impl Saveable for BotConfig {
    const FILE: &'static str = "config.json";
    const KIND: &'static str = "dev.kikugie.wavebot.server.BotConfig";
    fn empty() -> Self {
        BotConfig {
            source: String::new(),
            sheets: IndexMap::new(),
            offsets: IndexMap::new(),
            refresh: IsoDuration::default(),
            countdown: IsoDuration::default(),
            server: ServerData::default(),
        }
    }
}

impl Saveable for RuntimeData {
    const FILE: &'static str = "runtime.json";
    const KIND: &'static str = "dev.kikugie.wavebot.server.RuntimeData";
    fn empty() -> Self {
        RuntimeData::default()
    }
}

mod instant {
    use chrono::{DateTime, Utc};
    use serde::{Deserialize, Deserializer, Serializer};

    pub fn serialize<S: Serializer>(value: &Option<DateTime<Utc>>, serializer: S) -> Result<S::Ok, S::Error> {
        match value {
            Some(it) => serializer.serialize_str(&super::format_instant(*it)),
            None => serializer.serialize_none(),
        }
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<DateTime<Utc>>, D::Error> {
        let text = String::deserialize(deserializer)?;
        match DateTime::parse_from_rfc3339(&text) {
            Ok(it) => Ok(Some(it.with_timezone(&Utc))),
            Err(_) if text.starts_with('+') => Ok(None),
            Err(e) => Err(serde::de::Error::custom(e)),
        }
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct IsoDuration(pub TimeDelta);

impl Serialize for IsoDuration {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(&to_iso(self.0))
    }
}

impl<'de> Deserialize<'de> for IsoDuration {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let text = String::deserialize(deserializer)?;
        parse_iso(&text).map(IsoDuration).map_err(serde::de::Error::custom)
    }
}

impl std::fmt::Display for IsoDuration {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&display(self.0))
    }
}

pub fn parse_iso(text: &str) -> Result<TimeDelta> {
    let invalid = || anyhow::anyhow!("Invalid ISO duration string format: '{text}'");
    let (negative, rest) = match text.as_bytes().first() {
        Some(b'-') => (true, &text[1..]),
        Some(b'+') => (false, &text[1..]),
        _ => (false, text),
    };
    let rest = rest.strip_prefix('P').ok_or_else(invalid)?;
    if rest.is_empty() {
        return Err(invalid());
    }
    let (date, time) = match rest.split_once('T') {
        Some((_, "")) => return Err(invalid()),
        Some((d, t)) => (d, Some(t)),
        None => (rest, None),
    };
    let mut total = TimeDelta::zero();
    if !date.is_empty() {
        let days = date.strip_suffix('D').ok_or_else(invalid)?;
        total += TimeDelta::days(days.parse().map_err(|_| invalid())?);
    }
    if let Some(mut time) = time {
        for (unit, nanos) in [('H', 3_600_000_000_000i128), ('M', 60_000_000_000), ('S', 1_000_000_000)] {
            let Some(pos) = time.find(unit) else { continue };
            let number = &time[..pos];
            time = &time[pos + 1..];
            let (whole, frac) = number.split_once('.').unwrap_or((number, ""));
            if whole.is_empty() || !whole.bytes().all(|b| b.is_ascii_digit()) || !frac.bytes().all(|b| b.is_ascii_digit()) {
                return Err(invalid());
            }
            if !frac.is_empty() && !time.is_empty() {
                return Err(invalid());
            }
            let mut value = whole.parse::<i128>().map_err(|_| invalid())? * nanos;
            if !frac.is_empty() {
                let digits = &frac[..frac.len().min(18)];
                value += digits.parse::<i128>().map_err(|_| invalid())? * nanos / 10i128.pow(digits.len() as u32);
            }
            total += TimeDelta::nanoseconds(i64::try_from(value).map_err(|_| invalid())?);
        }
        if !time.is_empty() {
            return Err(invalid());
        }
    }
    Ok(if negative { -total } else { total })
}

fn fractional(out: &mut String, whole: i64, fraction: i64, digits: usize, unit: &str, iso: bool) {
    write!(out, "{whole}").unwrap();
    if fraction != 0 {
        let padded = format!("{fraction:0digits$}");
        let mut len = padded.trim_end_matches('0').len();
        if iso {
            len = len.div_ceil(3) * 3;
        }
        write!(out, ".{}", &padded[..len]).unwrap();
    }
    out.push_str(unit);
}

pub fn to_iso(value: TimeDelta) -> String {
    let mut out = String::new();
    if value < TimeDelta::zero() {
        out.push('-');
    }
    out.push_str("PT");
    let value = value.abs();
    let secs = value.num_seconds();
    let nanos = value.subsec_nanos() as i64;
    let (hours, minutes, seconds) = (secs / 3600, secs / 60 % 60, secs % 60);
    let has_hours = hours != 0;
    let has_seconds = seconds != 0 || nanos != 0;
    let has_minutes = minutes != 0 || (has_seconds && has_hours);
    if has_hours {
        write!(out, "{hours}H").unwrap();
    }
    if has_minutes {
        write!(out, "{minutes}M").unwrap();
    }
    if has_seconds || (!has_hours && !has_minutes) {
        fractional(&mut out, seconds, nanos, 9, "S", true);
    }
    out
}

pub fn display(value: TimeDelta) -> String {
    if value.is_zero() {
        return "0s".into();
    }
    let negative = value < TimeDelta::zero();
    let value = value.abs();
    let secs = value.num_seconds();
    let nanos = value.subsec_nanos() as i64;
    let (days, hours, minutes, seconds) = (secs / 86400, secs / 3600 % 24, secs / 60 % 60, secs % 60);
    let (has_days, has_hours, has_minutes, has_seconds) = (days != 0, hours != 0, minutes != 0, seconds != 0 || nanos != 0);
    let mut parts: Vec<String> = Vec::new();
    if has_days {
        parts.push(format!("{days}d"));
    }
    if has_hours || (has_days && (has_minutes || has_seconds)) {
        parts.push(format!("{hours}h"));
    }
    if has_minutes || (has_seconds && (has_hours || has_days)) {
        parts.push(format!("{minutes}m"));
    }
    if has_seconds {
        let mut out = String::new();
        if seconds != 0 || has_days || has_hours || has_minutes {
            fractional(&mut out, seconds, nanos, 9, "s", false);
        } else if nanos >= 1_000_000 {
            fractional(&mut out, nanos / 1_000_000, nanos % 1_000_000, 6, "ms", false);
        } else if nanos >= 1_000 {
            fractional(&mut out, nanos / 1_000, nanos % 1_000, 3, "us", false);
        } else {
            write!(out, "{nanos}ns").unwrap();
        }
        parts.push(out);
    }
    let joined = parts.join(" ");
    match (negative, parts.len()) {
        (false, _) => joined,
        (true, 1) => format!("-{joined}"),
        (true, _) => format!("-({joined})"),
    }
}

pub fn format_instant(value: DateTime<Utc>) -> String {
    value.to_rfc3339_opts(SecondsFormat::AutoSi, true)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    #[test]
    fn iso_durations() {
        assert_eq!(parse_iso("PT1H").unwrap(), TimeDelta::hours(1));
        assert_eq!(parse_iso("PT72H").unwrap(), TimeDelta::hours(72));
        assert_eq!(parse_iso("P1DT2H").unwrap(), TimeDelta::hours(26));
        assert_eq!(parse_iso("PT1.5S").unwrap(), TimeDelta::milliseconds(1500));
        assert_eq!(parse_iso("-PT5M").unwrap(), TimeDelta::minutes(-5));
        assert!(parse_iso("PT").is_err());
        assert!(parse_iso("1H").is_err());
        assert_eq!(to_iso(TimeDelta::zero()), "PT0S");
        assert_eq!(to_iso(TimeDelta::hours(72)), "PT72H");
        assert_eq!(to_iso(TimeDelta::milliseconds(3_601_500)), "PT1H0M1.500S");
    }

    #[test]
    fn kotlin_display() {
        assert_eq!(display(TimeDelta::hours(1)), "1h");
        assert_eq!(display(TimeDelta::hours(72)), "3d");
        assert_eq!(display(TimeDelta::minutes(90)), "1h 30m");
        assert_eq!(display(TimeDelta::seconds(86_401)), "1d 0h 0m 1s");
        assert_eq!(display(TimeDelta::milliseconds(1500)), "1.5s");
        assert_eq!(display(TimeDelta::microseconds(1500)), "1.5ms");
        assert_eq!(display(TimeDelta::minutes(-90)), "-(1h 30m)");
        assert_eq!(display(TimeDelta::zero()), "0s");
    }

    const FIXTURE: &str = r#"{
  "type": "dev.kikugie.wavebot.server.RuntimeData",
  "messages": {
    "1300000000000000001": {
      "type": "creative",
      "index": 0
    }
  },
  "tickets": {
    "859730791135444993": {
      "member": 859730791135444993,
      "channel": 0,
      "state": "REJECTED"
    },
    "859730791135444994": {
      "member": 859730791135444994,
      "channel": 1300000000000000002,
      "state": "REJECTED",
      "countdown": "2025-12-29T22:57:31.379903577Z"
    }
  },
  "applications": {
    "creative": [
      {
        "type": "CREATIVE",
        "discord": "someone",
        "minecraft": "Steve",
        "age": "20",
        "answers": [
          {
            "B question": "b",
            "A question": "a"
          }
        ]
      }
    ]
  }
}"#;

    fn round_trip<T: Saveable>(text: &str) {
        let parsed: T = serde_json::from_str(text).unwrap();
        let mut expected: Value = serde_json::from_str(text).unwrap();
        let mut actual = serde_json::to_value(&parsed).unwrap();
        expected.as_object_mut().unwrap().remove("type");
        actual.as_object_mut().unwrap().remove("type");
        assert_eq!(expected, actual);
    }

    #[test]
    fn runtime_fixture() {
        round_trip::<RuntimeData>(FIXTURE);
        let parsed: RuntimeData = serde_json::from_str(FIXTURE).unwrap();
        let app = parsed.entry(1300000000000000001).unwrap();
        assert_eq!(app.pronouns, "?");
        assert_eq!(app.answers[0].keys().collect::<Vec<_>>(), ["B question", "A question"]);
        assert!(parsed.tickets[&859730791135444993].countdown.is_none());
        assert_eq!(
            format_instant(parsed.tickets[&859730791135444994].countdown.unwrap()),
            "2025-12-29T22:57:31.379903577Z"
        );
    }

    #[test]
    fn real_files() {
        if let Ok(text) = std::fs::read_to_string(RuntimeData::FILE) {
            round_trip::<RuntimeData>(&text);
            let parsed: RuntimeData = serde_json::from_str(&text).unwrap();
            assert!(parsed.to_json().unwrap() == text, "runtime.json is not byte-identical after re-serializing");
        }
        if let Ok(text) = std::fs::read_to_string(BotConfig::FILE) {
            round_trip::<BotConfig>(&text);
        }
    }
}
