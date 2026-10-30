use anyhow::{Result, anyhow};
use indexmap::IndexMap;
use regex::Regex;
use serde::{Deserialize, Serialize};
use serenity::all::{ChannelId, CreateEmbed, CreateMessage, Http, Member, UserId};
use std::sync::LazyLock;

const IDK: &str = "?";

fn is_idk(value: &String) -> bool {
    value == IDK
}

fn idk() -> String {
    IDK.into()
}

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "UPPERCASE")]
pub enum ApplicationType {
    General,
    Creative,
    Builder,
}

impl ApplicationType {
    pub fn text(self) -> &'static str {
        match self {
            Self::General => "general",
            Self::Creative => "creative",
            Self::Builder => "builder",
        }
    }

    pub fn id(self) -> char {
        self.text().chars().next().unwrap()
    }

    pub fn color(self) -> u32 {
        match self {
            Self::General => 0xFF0000,
            Self::Creative => 0x0000FF,
            Self::Builder => 0x00FF00,
        }
    }

    fn groups(self) -> &'static [&'static [usize]] {
        match self {
            Self::General => &[&[6, 7, 8], &[9, 10, 11, 12, 13], &[14]],
            Self::Creative => &[&[6, 7, 8, 9, 10], &[11]],
            Self::Builder => &[&[6, 7, 8, 9], &[10]],
        }
    }

    pub fn of(value: &str) -> Result<Self> {
        match value.to_uppercase().as_str() {
            "GENERAL" => Ok(Self::General),
            "CREATIVE" => Ok(Self::Creative),
            "BUILDER" => Ok(Self::Builder),
            other => Err(anyhow!("No enum constant dev.kikugie.wavebot.sheet.ApplicationType.{other}")),
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct ApplicationData {
    #[serde(rename = "type")]
    pub kind: ApplicationType,
    #[serde(default = "idk", skip_serializing_if = "is_idk")]
    pub discord: String,
    #[serde(default = "idk", skip_serializing_if = "is_idk")]
    pub minecraft: String,
    #[serde(default = "idk", skip_serializing_if = "is_idk")]
    pub pronouns: String,
    #[serde(default = "idk", skip_serializing_if = "is_idk")]
    pub age: String,
    #[serde(default = "idk", skip_serializing_if = "is_idk")]
    pub timezone: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub answers: Vec<IndexMap<String, String>>,
}

fn is_blank(value: &str) -> bool {
    value.chars().all(char::is_whitespace)
}

fn get_or_idk(data: &[String], index: usize) -> String {
    data.get(index).filter(|it| !is_blank(it)).cloned().unwrap_or_else(idk)
}

fn limit_to(value: &str, n: usize) -> String {
    if value.chars().count() <= n {
        value.into()
    } else {
        format!("{}...", value.chars().take(n - 3).collect::<String>())
    }
}

impl ApplicationData {
    pub fn parse(kind: &str, keys: &[String], data: &[String]) -> Result<Self> {
        let kind = ApplicationType::of(kind)?;
        let data: Vec<String> = data.iter().map(|it| it.trim_matches(|c: char| c <= ' ').to_string()).collect();
        let mut app = ApplicationData {
            kind,
            discord: get_or_idk(&data, 1),
            minecraft: get_or_idk(&data, 2),
            age: get_or_idk(&data, 3),
            timezone: get_or_idk(&data, 4),
            pronouns: get_or_idk(&data, 5),
            answers: Vec::new(),
        };
        for group in kind.groups() {
            let mut map = IndexMap::new();
            for &index in *group {
                let key = keys
                    .get(index)
                    .ok_or_else(|| anyhow!("Index {index} out of bounds for length {}", keys.len()))?;
                if let Some(value) = data.get(index).filter(|it| !is_blank(it)) {
                    map.insert(key.clone(), value.clone());
                }
            }
            if !map.is_empty() {
                app.answers.push(map);
            }
        }
        Ok(app)
    }

    pub fn title(&self) -> String {
        format!("{} {} application", referring(&self.minecraft), self.kind.text())
    }

    pub fn preview(&self) -> CreateEmbed {
        CreateEmbed::new()
            .color(self.kind.color())
            .title(self.title())
            .field("Discord", &self.discord, true)
            .field("IGN", &self.minecraft, true)
            .field("Age", &self.age, true)
            .field("Pronouns", &self.pronouns, true)
            .field("Timezone", &self.timezone, true)
    }

    pub async fn contents(&self, http: &Http, channel: ChannelId) -> Result<()> {
        let mut links = Vec::new();
        for group in &self.answers {
            if group.is_empty() {
                continue;
            }
            let mut embed = CreateEmbed::new().color(self.kind.color());
            for (key, answer) in group {
                links.extend(find_links(answer));
                embed = embed.field(key, limit_to(answer, 1000), false);
            }
            channel.send_message(http, CreateMessage::new().embed(embed)).await?;
        }
        for chunk in links.chunks(5) {
            channel.send_message(http, CreateMessage::new().content(chunk.join("\n"))).await?;
        }
        Ok(())
    }

    pub async fn find_member(&self) -> Result<Option<Member>> {
        find_member(&self.discord).await
    }
}

pub async fn find_member(discord: &str) -> Result<Option<Member>> {
    let state = crate::state();
    let mut after: Option<UserId> = None;
    loop {
        let page = state.guild.members(&state.http, Some(1000), after).await?;
        let Some(last) = page.last() else { return Ok(None) };
        after = Some(last.user.id);
        let full = page.len() == 1000;
        if let Some(found) = page.into_iter().find(|it| {
            let user = &it.user;
            let tag = format!("{}#{}", user.name, user.discriminator.map_or("0".into(), |d| format!("{d:04}")));
            user.name == discord || tag == discord || it.display_name() == discord
        }) {
            return Ok(Some(found));
        }
        if !full {
            return Ok(None);
        }
    }
}

pub fn referring(value: &str) -> String {
    match value.chars().last() {
        Some('s' | 'x') => format!("{value}'"),
        _ => format!("{value}'s"),
    }
}

static URL_PATTERN: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"[(htps)?:/w.a-zA-Z0-9@%_+~#=]{2,256}\.[a-z]{2,6}(?-u:\b)([-a-zA-Z0-9@:%_+.~#?&/=]*)").unwrap()
});

fn java_url(text: &str) -> Option<url::Url> {
    let (scheme, _) = text.split_once(':')?;
    let mut chars = scheme.chars();
    if !chars.next()?.is_ascii_alphabetic() || !chars.all(|c| c.is_ascii_alphanumeric() || "+-.".contains(c)) {
        return None;
    }
    if !["http", "https", "ftp", "file", "jar", "mailto"].contains(&scheme.to_ascii_lowercase().as_str()) {
        return None;
    }
    if text.matches('#').count() > 1 {
        return None;
    }
    let bytes = text.as_bytes();
    for (i, b) in bytes.iter().enumerate() {
        if *b == b'%' && !(bytes.get(i + 1).is_some_and(u8::is_ascii_hexdigit) && bytes.get(i + 2).is_some_and(u8::is_ascii_hexdigit)) {
            return None;
        }
    }
    url::Url::parse(text).ok()
}

pub fn find_links(text: &str) -> Vec<String> {
    URL_PATTERN
        .find_iter(text)
        .map(|it| it.as_str())
        .filter_map(|it| {
            let url = java_url(it)?;
            Some(match url.host_str() {
                Some("drive.google.com") => {
                    let id = it.split_once("?id=").map_or(it, |(_, id)| id);
                    format!("https://drive.google.com/thumbnail?id={id}&sz=w1920")
                }
                _ => it.to_string(),
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn strings(values: &[&str]) -> Vec<String> {
        values.iter().map(|it| it.to_string()).collect()
    }

    #[test]
    fn parse_rows() {
        let keys: Vec<String> = (0..15).map(|i| format!("Q{i}")).collect();
        let data = strings(&["time", " user ", "Steve", "", "UTC", "they/them", "a", "", "c", "d", "e", "f", "g", "h", "i"]);

        let general = ApplicationData::parse("general", &keys, &data).unwrap();
        assert_eq!((general.discord.as_str(), general.minecraft.as_str(), general.age.as_str()), ("user", "Steve", "?"));
        assert_eq!(general.pronouns, "they/them");
        assert_eq!(general.answers.len(), 3);
        assert_eq!(general.answers[0].keys().collect::<Vec<_>>(), ["Q6", "Q8"]);
        assert_eq!(general.answers[1].len(), 5);
        assert_eq!(general.answers[2]["Q14"], "i");

        let builder = ApplicationData::parse("builder", &keys, &data).unwrap();
        assert_eq!(builder.answers.iter().map(IndexMap::len).collect::<Vec<_>>(), [3, 1]);

        let empty_group = ApplicationData::parse("creative", &keys, &strings(&["t", "u", "m"])).unwrap();
        assert!(empty_group.answers.is_empty());

        assert!(ApplicationData::parse("general", &keys[..10], &data).is_err());
        assert!(ApplicationData::parse("unknown", &keys, &data).is_err());
    }

    #[test]
    fn referring_names() {
        assert_eq!(referring("Steve"), "Steve's");
        assert_eq!(referring("Chris"), "Chris'");
        assert_eq!(referring("Max"), "Max'");
    }

    #[test]
    fn links() {
        assert_eq!(find_links("see https://example.com/a?b=c here"), ["https://example.com/a?b=c"]);
        assert!(find_links("bare example.com link").is_empty());
        assert_eq!(
            find_links("https://drive.google.com/open?id=ABC"),
            ["https://drive.google.com/thumbnail?id=ABC&sz=w1920"]
        );
        assert_eq!(
            find_links("https://drive.google.com/file/d/X/view"),
            ["https://drive.google.com/thumbnail?id=https://drive.google.com/file/d/X/view&sz=w1920"]
        );
        assert_eq!(limit_to(&"a".repeat(1001), 1000).len(), 1000);
    }
}
