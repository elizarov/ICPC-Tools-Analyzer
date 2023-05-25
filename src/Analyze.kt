import java.io.*
import java.text.*
import java.util.*
import java.util.zip.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.*

const val CONTEST_TIMEZONE = "Asia/Dhaka"
const val RESULT_DIR = "result"

// OUTPUT FILES (INPUT FILES ARE GIVEN IN THE PROGRAM ARGS)
const val UNIDENTIFIED_TOOLS_FILE = "icpc-unidentified-tools.txt"
const val UNEXPECTED_SUBMISSION_TOOLS_FILE = "icpc-unexpected-submission-tools.txt"
const val TOOLS_FILE = "icpc-tools.csv"
const val TEAMS_TIME_FILE = "icpc-teams-time.csv"
const val TEAMS_NAME_FILE = "icpc-teams-name.csv"
const val LANGS_SUBMITTED_FILE = "icpc-langs-submitted.csv"
const val LANGS_ACCEPTED_FILE = "icpc-langs-accepted.csv"

const val PREF = "ps.team"
const val SUFF = ".txt"
val WS = Regex("\\s+")

val INTERVAL = 10 * 60 // 10 min

enum class Tool(vararg val paths: String, val languages: List<Language> = LANGUAGES) {
    CLion("/opt/clion", "/usr/bin/clion", "clion", languages = listOf(Language.C)),
    Idea("/usr/lib/idea", "idea", languages = listOf(Language.Java, Language.Kotlin)),
    Pycharm("/usr/lib/pycharm", "pycharm", languages = listOf(Language.Python)),
    Eclipse("/usr/lib/eclipse", "/usr/bin/java -Dosgi.requiredJavaVersion=1.8", "eclipse", languages = listOf(Language.Java)),
    CodeBlocks("/usr/bin/codeblocks", "codeblocks", languages = listOf(Language.C)),
    Geany("/usr/bin/geany", "geany"),
    Emacs("/usr/bin/emacs", "emacs"),
    GEdit("/usr/bin/gedit", "gedit"),
    Vim("/usr/bin/vim", "vim", "gvim"),
    Vi("/usr/bin/vi", "vi"),
    VSCode("/usr/share/code", "/usr/bin/code", "vscode", languages = listOf(Language.C, Language.Java, Language.Python)),
    Kate("/usr/bin/kate", "kate"),
    Nano("nano"),
    Unknown(languages = emptyList()) // must be last
}

val TOOLS = Tool.values().filter { it != Tool.Unknown }
val TOOLS_WITH_UNKNOWN = Tool.values().toList()

class Usage(val time: Long, val team: String, val tool: Tool, val cpu: Double)

fun String.teamIdOrNull() =
    substringAfterLast("/").
    takeIf { it.startsWith(PREF) && it.endsWith(SUFF) }
    ?.removeSurrounding(PREF, SUFF)

fun main(args: Array<String>) {
    if (args.size != 2) {
        println("Usage: Analyze <event-feed-json> <tool-backup-zip>")
        println(" <event-feed-json> -- JSON file with event feed")
        println(" <tool-backup-zip> -- ZIP file with 'ps.team<team>.txt' files")
        return
    }
    val eventFeedFile = args[0]
    val toolBackupFile = args[1]

    val eventFeed = EventFeed(eventFeedFile)
    val zipFile = ZipFile(toolBackupFile)

    val teamEntries = zipFile.entries().toList()
        .filter { ze -> !ze.isDirectory }
        .mapNotNull { ze: ZipEntry -> ze.name.teamIdOrNull()?.let { it to ze } }
        .filter { eventFeed.teams[it.first] != null }
        .sortedBy { it.first.teamIdSortKey() }
    val teams = teamEntries.map { it.first }
    val usage = ArrayList<Usage>()
    val unidentifiedToolCmds = HashSet<String>()
    val toolCpu = DoubleArray(TOOLS.size)
    val allTimes = ArrayList<Long>()

    println("Found ${teamEntries.size} team ps files")

    for ((team, zipEntry) in teamEntries) {
        println("Parsing ${zipEntry.name}")

        var time = 0L

        fun flushUsage() {
            if (time == 0L) return
            allTimes += time
            for (tool in TOOLS) {
                val value = toolCpu[tool.ordinal]
                if (value != 0.0) {
                    usage += Usage(time, team, tool, value)
                    toolCpu[tool.ordinal] = 0.0
                }
            }
        }

        zipFile.getInputStream(zipEntry).reader().forEachLine { line ->
            line.toLongOrNull()?.let { t ->
                flushUsage()
                time = t
                return@forEachLine
            }
            // USER PID %CPU %MEM VSZ RSS TTY STAT START TIME COMMAND
            // 0    1   2    3    4   5   6   7    8     9    10
            val tokens = line.split(WS, 11)
            val user = tokens[0]
            if (user != "team$team") return@forEachLine
            val cmd = tokens.getOrNull(10) ?: return@forEachLine
            val tool = TOOLS.firstOrNull { it.paths.any { cmd.startsWith(it) } } ?: run {
                unidentifiedToolCmds += cmd
                return@forEachLine
            }
            val cpu = tokens[2].toDoubleOrNull() ?: return@forEachLine
            toolCpu[tool.ordinal] = max(toolCpu[tool.ordinal] + cpu, 0.01) // make it always non-zero on usage
        }
        flushUsage()
    }
    println("Parsed ps files for ${teamEntries.size} teams")
    println("Found ps data in time range (seconds from epoch):")
    println("  Min: ${allTimes.min()}")
    println("  Max: ${allTimes.max()}")

    // ---------------------------- OUTPUT ----------------------------

    File(RESULT_DIR).mkdirs()

    // unidentified tools
    scope {
        val out = writeFile(UNIDENTIFIED_TOOLS_FILE)
        unidentifiedToolCmds.sorted().forEach { cmd ->
            out.println(cmd)
        }
    }

    // sort usage
    usage.sortBy { it.time }

    // write usage
    val timeFormat = SimpleDateFormat("HH:mm").apply {
        timeZone = TimeZone.getTimeZone(CONTEST_TIMEZONE)
    }
    val intervalTeamTopTool = mutableMapOf<Long, Map<String, Tool>>()
    scope {
        val toolsOut = writeFile(TOOLS_FILE)
        val teamsOut = writeFile(TEAMS_TIME_FILE)
        toolsOut.println("TIME,${TOOLS.joinToString(",")}")
        teamsOut.println("TIME,${teams.joinToString(",")}")
        var cur = 0L
        val teamTools = HashMap<String, DoubleArray>()

        fun flushInterval() {
            if (cur == 0L) return
            val topTool = teamTools.mapNotNull { e ->
                val i = e.value.withIndex().maxBy { it.value }
                e.value.fill(0.0)
                if (i.value == 0.0) null else e.key to TOOLS[i.index]
            }.toMap()
            intervalTeamTopTool[cur] = topTool
            val counts = topTool.values.groupingBy { it }.eachCount()
            if (counts.isEmpty()) return
            val time = timeFormat.format(cur * INTERVAL * 1000)
            toolsOut.println("$time,${TOOLS.joinToString(",") { (counts[it] ?: 0).toString() }}")
            teamsOut.println("$time,${teams.joinToString(",") { topTool[it]?.name ?: "--" }}")
        }

        for (u in usage) {
            val i = u.time / INTERVAL
            if (i != cur) {
                flushInterval()
                cur = i
            }
            val a = teamTools.getOrPut(u.team) { DoubleArray(TOOLS.size) }
            a[u.tool.ordinal] += u.cpu
        }
        flushInterval()
    }

    // analyze accepted runs and write unexpected tools / submissions
    val langToolCountSubmitted = HashMap<Language, IntArray>()
    val langToolCountAccepted = HashMap<Language, IntArray>()
    scope {
        val out = writeFile(UNEXPECTED_SUBMISSION_TOOLS_FILE)
        out.println("TIME,TEAM,LANGUAGE,TOOL")
        for (submission in eventFeed.submissions.values) {
            val interval = submission.time / (INTERVAL * 1000L)
            val teamTopTool = intervalTeamTopTool[interval] ?: continue // time out of range
            val tool = teamTopTool[submission.teamId] ?: Tool.Unknown
            if (submission.language !in tool.languages) {
                out.println("${submission.time / 1000L},${submission.teamId},${submission.language},$tool")
            }
            val subCount = langToolCountSubmitted.getOrPut(submission.language) { IntArray(TOOLS_WITH_UNKNOWN.size) }
            subCount[tool.ordinal]++
            if (submission.accepted) {
                val accCount = langToolCountAccepted.getOrPut(submission.language) { IntArray(TOOLS_WITH_UNKNOWN.size) }
                accCount[tool.ordinal]++
            }
        }
    }

    scope {
        val subOut = writeFile(LANGS_SUBMITTED_FILE)
        val accOut = writeFile(LANGS_ACCEPTED_FILE)
        subOut.println("LANG,${TOOLS_WITH_UNKNOWN.joinToString(",")}")
        accOut.println("LANG,${TOOLS_WITH_UNKNOWN.joinToString(",")}")
        for (lang in LANGUAGES) {
            langToolCountSubmitted[lang]?.let { subCount ->
                subOut.println("$lang,${TOOLS_WITH_UNKNOWN.joinToString(",") { subCount[it.ordinal].toString() }}")
            }
            langToolCountAccepted[lang]?.let { accCount ->
                accOut.println("$lang,${TOOLS_WITH_UNKNOWN.joinToString(",") { accCount[it.ordinal].toString() }}")
            }
        }
    }

    scope {
        val out = writeFile(TEAMS_NAME_FILE)
        out.println("TEAM,NAME")
        for (team in teams) {
            out.println("$team,${eventFeed.teams[team]}")
        }
    }
}

class DeferScope {
    private val deferred = ArrayList<() -> Unit>()

    fun defer(block: () -> Unit) {
        deferred += block
    }

    fun close() {
        deferred.forEach { it() }
    }
}

inline fun scope(block: DeferScope.() -> Unit) {
    val scope = DeferScope()
    try {
        scope.block()
    } finally {
        scope.close()
    }
}

fun DeferScope.writeFile(fileName: String): PrintWriter {
    val file = File(RESULT_DIR, fileName)
    println("Writing $file ...")
    return file.printWriter().also { defer { it.close() } }
}