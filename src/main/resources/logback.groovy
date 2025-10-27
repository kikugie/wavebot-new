import ch.qos.logback.core.joran.spi.ConsoleTarget
import ch.qos.logback.core.ConsoleAppender

def defaultLevel = INFO
def defaultTarget = ConsoleTarget.SystemErr

def DEV_MODE = System.getProperty("devMode")?.toBoolean() ||
        System.getenv("DEV_MODE") != null ||
        ["dev", "development"].contains(System.getenv("ENVIRONMENT"))

if (DEV_MODE) {
    defaultLevel = DEBUG
    defaultTarget = ConsoleTarget.SystemOut

    // Silence warning about missing native PRNG
    logger("io.ktor.util.random", ERROR)
}

appender("CONSOLE", ConsoleAppender) {
    encoder(PatternLayoutEncoder) {
        pattern = "%boldGreen(%d{yyyy-MM-dd}) %boldYellow(%d{HH:mm:ss}) %gray(|) %highlight(%5level) %gray(|) %boldMagenta(%40.40logger{40}) %gray(|) %msg%n"
    }

    target = defaultTarget
}

root(defaultLevel, ["CONSOLE"])