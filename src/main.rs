macro_rules! debug {
    ($($arg:tt)*) => { log::debug!(target: "Wavebot", $($arg)*) };
}
macro_rules! info {
    ($($arg:tt)*) => { log::info!(target: "Wavebot", $($arg)*) };
}
macro_rules! error {
    ($($arg:tt)*) => { log::error!(target: "Wavebot", $($arg)*) };
}

mod application;
mod channels;
mod commands;
mod data;
mod spreadsheet;

use anyhow::Context;
use chrono::Utc;
use data::{BotConfig, RuntimeData, Saveable, Snowflake, Ticket, TicketState};
use log::{Level, LevelFilter};
use serenity::all::{GatewayIntents, GuildId, Http};
use std::sync::{Arc, Mutex, OnceLock, RwLock};

pub struct State {
    pub http: Arc<Http>,
    pub guild: GuildId,
    pub google_token: String,
    pub config: RwLock<BotConfig>,
    pub storage: Mutex<RuntimeData>,
}

static STATE: OnceLock<State> = OnceLock::new();

pub fn state() -> &'static State {
    STATE.get().expect("State is not initialized")
}

fn env(key: &str) -> anyhow::Result<String> {
    std::env::var(key).with_context(|| format!("Environment variable '{key}' is not set"))
}

struct Quiet(env_logger::Logger);

impl log::Log for Quiet {
    fn enabled(&self, metadata: &log::Metadata) -> bool {
        self.0.enabled(metadata)
    }

    fn log(&self, record: &log::Record) {
        if record.level() <= Level::Warn || !record.args().to_string().contains("heartbeat") {
            self.0.log(record);
        }
    }

    fn flush(&self) {
        self.0.flush();
    }
}

fn logging() {
    let dev = std::env::var_os("DEV_MODE").is_some()
        || matches!(std::env::var("ENVIRONMENT").as_deref(), Ok("dev" | "development"));
    let logger = env_logger::Builder::new()
        .filter_level(if dev { LevelFilter::Debug } else { LevelFilter::Info })
        .filter_module("tracing::span", LevelFilter::Off)
        .target(if dev { env_logger::Target::Stdout } else { env_logger::Target::Stderr })
        .build();
    log::set_max_level(logger.filter());
    log::set_boxed_logger(Box::new(Quiet(logger))).unwrap();
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    dotenvy::dotenv().ok();
    logging();

    let guild = GuildId::new(env("TEST_SERVER")?.parse()?);
    let token = env("DISCORD_TOKEN")?;
    let google_token = env("GOOGLE_TOKEN")?;
    let config = BotConfig::load()?;
    let storage = RuntimeData::load()?;
    let http = Arc::new(Http::new(&token));
    guild.to_partial_guild(&http).await?;
    let _ = STATE.set(State {
        http,
        guild,
        google_token,
        config: RwLock::new(config),
        storage: Mutex::new(storage),
    });

    let framework = poise::Framework::builder()
        .options(poise::FrameworkOptions {
            commands: commands::all(),
            event_handler: commands::event_handler,
            ..Default::default()
        })
        .setup(move |ctx, _ready, framework| {
            Box::pin(async move {
                poise::builtins::register_in_guild(ctx, &framework.options().commands, guild).await?;
                Ok(())
            })
        })
        .build();

    tokio::spawn(ping());
    serenity::Client::builder(&token, GatewayIntents::non_privileged())
        .framework(framework)
        .await?
        .start()
        .await?;
    Ok(())
}

async fn ping() {
    let mut n = 0;
    loop {
        n += 1;
        let refresh = state().config.read().unwrap().refresh;
        info!("Pinging server every {refresh} ({n} attempt)");
        update().await;
        let refresh = state().config.read().unwrap().refresh;
        tokio::time::sleep(refresh.0.to_std().unwrap_or_default()).await;
    }
}

pub async fn update() {
    let state = state();
    let sheets: Vec<String> = state.config.read().unwrap().sheets.keys().cloned().collect();
    for kind in sheets {
        let result = match spreadsheet::request(&kind).await {
            Ok(table) => spreadsheet::update(&kind, table),
            Err(e) => Err(e),
        };
        match result {
            Ok(new) => {
                info!("Received {} new applications for '{kind}'", new.len());
                for (index, data) in new {
                    if let Err(e) = channels::preview(index, &data).await {
                        error!("Failed to create post for {} ({kind})\n{e:?}", data.minecraft);
                    }
                }
            }
            Err(e) => error!("Failed to fetch spreadsheet for '{kind}'\n{e:?}"),
        }
    }

    let now = Utc::now();
    let countdown = state.config.read().unwrap().countdown.0;
    let expired: Vec<(Snowflake, Ticket)> = state
        .storage
        .lock()
        .unwrap()
        .tickets
        .iter()
        .filter(|(_, it)| it.state == TicketState::Rejected && it.countdown.is_some_and(|c| now - c >= countdown))
        .map(|(id, it)| (*id, it.clone()))
        .collect();
    if expired.is_empty() {
        return;
    }

    let mut removal = Vec::new();
    for (id, ticket) in expired {
        let result: anyhow::Result<()> = async {
            let name = channels::guild_channel(ticket.channel).await?.map(|it| it.name);
            info!("Deleting #{}", name.as_deref().unwrap_or("null"));
            removal.push(id);
            channels::delete(&ticket).await
        }
        .await;
        if let Err(e) = result {
            error!("Failed to delete the channel\n{e:?}");
        }
    }

    let mut storage = state.storage.lock().unwrap();
    for id in removal {
        storage.tickets.shift_remove(&id);
    }
    if let Err(e) = storage.save() {
        error!("Failed to save runtime data\n{e:?}");
    }
}
