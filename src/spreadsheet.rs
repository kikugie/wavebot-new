use crate::application::ApplicationData;
use crate::data::Saveable;
use anyhow::{Context, Result};
use serde::Deserialize;
use std::sync::LazyLock;

static CLIENT: LazyLock<reqwest::Client> = LazyLock::new(reqwest::Client::new);

#[derive(Deserialize)]
pub struct Table {
    pub range: String,
    #[serde(default)]
    pub keys: Vec<String>,
    pub values: Vec<Vec<String>>,
}

pub async fn request(kind: &str) -> Result<Table> {
    let state = crate::state();
    let (source, sheet, offset) = {
        let config = state.config.read().unwrap();
        let sheet = config.sheets.get(kind).with_context(|| format!("No sheet for '{kind}'"))?.clone();
        (config.source.clone(), sheet, config.offsets.get(kind).copied().unwrap_or(0))
    };
    let url = format!(
        "https://sheets.googleapis.com/v4/spreadsheets/{source}/values/{sheet}?alt=json&key={}",
        state.google_token
    )
    .replace(' ', "%20");
    let body = CLIENT.get(url).send().await?.text().await?;
    let mut table: Table = serde_json::from_str(&body)?;
    let keys = table.values.first().context("Spreadsheet has no rows")?.clone();
    table.values = table.values.into_iter().skip(offset + 1).collect();
    table.keys = keys;
    Ok(table)
}

pub fn update(kind: &str, data: Table) -> Result<Vec<(usize, ApplicationData)>> {
    let mut storage = crate::state().storage.lock().unwrap();
    let container = storage.applications.entry(kind.to_string()).or_default();
    let start = container.len();
    if start >= data.values.len() {
        return Ok(Vec::new());
    }
    let new = data.values[start..]
        .iter()
        .map(|row| ApplicationData::parse(kind, &data.keys, row))
        .collect::<Result<Vec<_>>>()?;
    container.extend(new.iter().cloned());
    storage.save()?;
    Ok(new.into_iter().enumerate().map(|(i, it)| (start + i, it)).collect())
}
