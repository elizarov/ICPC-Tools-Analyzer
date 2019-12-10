import java.io.*
import java.text.*
import kotlin.math.*

// OUTPUT FILES (INPUT FILES ARE GIVEN IN THE PROGRAM ARGS)
val TOOLS_FILE = "icpc-tools.csv"
val TEAMS_TIME_FILE = "icpc-teams-time.csv"
val TEAMS_NAME_FILE = "icpc-teams-name.csv"
val LANGS_SUBMITTED_FILE = "icpc-langs-submitted.csv"
val LANGS_ACCEPTED_FILE = "icpc-langs-accepted.csv"

val PREF = "ps."
val SUFF = ".txt"
val WS = Regex("\\s+")

val INTERVAL = 5 * 60 // 5 min

enum class Tool(vararg val paths: String) {
    CLion("/opt/clion", "clion"),
    Idea("/usr/lib/idea", "idea"),
    Pycharm("/usr/lib/pycharm", "pycharm"),
    Eclipse("/usr/lib/eclipse", "/usr/bin/java -Dosgi.requiredJavaVersion=1.8", "eclipse"),
    CodeBlocks("/usr/bin/codeblocks", "codeblocks"),
    Geany("/usr/bin/geany"),
    Emacs("/usr/bin/emacs", "emacs"),
    GEdit("/usb/bin/gedit", "gedit"),
    Vim("/usr/bin/vim", "vim"),
    Vi("/usr/bin/vi", "vi"),
    Kate("/usr/bin/kate", "kate"),
    Unknown // must be last
}

val TOOLS = Tool.values().filter { it != Tool.Unknown }
val TOOLS_WITH_UNKNOWN = Tool.values().toList()

class Usage(val time: Long, val team: String, val tool: Tool, val cpu: Double)

fun String.teamName() = substring(PREF.length, length - SUFF.length)
fun String.sortNorm() = replace("team", "").padStart(3, '0')

fun main(args: Array<String>) {
    if (args.size != 2) {
        println("Usage: Analyze <event-feed-json> <ps-files-dir>")
        println(" <event-feed-json> -- JSON file with event feed")
        println(" <ps-files-dir>    -- Directory with 'ps.<team>.txt' files")
        return
    }
    val eventFeedFile = args[0]
    val psFilesDir = args[1]
    val files = File(psFilesDir).listFiles { _, name: String ->
        name.startsWith(PREF) && name.endsWith(SUFF)
    }!!.sortedBy {
        it.name.teamName().sortNorm()
    }
    val teams = ArrayList<String>()
    val usage = ArrayList<Usage>()
    val toolCpu = DoubleArray(TOOLS.size)

// todo:
//    val teamToolsCount = HashMap<String, IntArray>()

    for (file in files) {
        val team = file.name.teamName()
        var time = 0L
        teams += team

        fun flushUsage() {
            for (tool in TOOLS) {
                val value = toolCpu[tool.ordinal]
                if (value != 0.0) {
                    usage += Usage(time, team, tool, value)
                    toolCpu[tool.ordinal] = 0.0
                }
            }
        }

        println("Parsing $file")

        file.forEachLine { line ->
            line.toLongOrNull()?.let { t ->
                flushUsage()
                time = t
                return@forEachLine
            }
            // USER PID %CPU %MEM VSZ RSS TTY STAT START TIME COMMAND
            // 0    1   2    3    4   5   6   7    8     9    10
            val tokens = line.split(WS, 11)
            val user = tokens[0]
            if (user != team) return@forEachLine
            val cmd = tokens.getOrNull(10) ?: return@forEachLine
            val tool = TOOLS.firstOrNull { it.paths.any { cmd.startsWith(it) } } ?: return@forEachLine
            val cpu = tokens[2].toDoubleOrNull() ?: return@forEachLine
            toolCpu[tool.ordinal] = max(toolCpu[tool.ordinal] + cpu, 0.01) // make it always non-zero on usage
        }
        flushUsage()
    }
    // sort
    teams.sortBy { it.sortNorm() }
    usage.sortBy { it.time }
    // write
    println("Writing $TOOLS_FILE and $TEAMS_TIME_FILE")
    val intervalTeamTopTool = mutableMapOf<Long, Map<String, Tool>>()
    scope {
        val toolsOut = writeFile(TOOLS_FILE)
        val teamsOut = writeFile(TEAMS_TIME_FILE)
        toolsOut.println("TIME,${TOOLS.joinToString(",")}")
        teamsOut.println("TIME,${teams.joinToString(",")}")
        var cur = 0L
        val teamTools = HashMap<String, DoubleArray>()

        fun flushInterval() {
            val topTool = teamTools.mapNotNull { e ->
                val i = e.value.withIndex().maxBy { it.value }!!
                e.value.fill(0.0)
                if (i.value == 0.0) null else e.key to TOOLS[i.index]
            }.toMap()
            intervalTeamTopTool[cur] = topTool
            val counts = topTool.values.groupingBy { it }.eachCount()
            if (counts.isEmpty()) return
            val time = SimpleDateFormat("HH:mm").format(cur * INTERVAL * 1000)
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
    // analyze accepted runs
    val eventFeed = EventFeed(eventFeedFile)
    val langToolCountSubmitted = HashMap<Language, IntArray>()
    val langToolCountAccepted = HashMap<Language, IntArray>()
    for (submission in eventFeed.submissions.values) {
        val internal = submission.time / (INTERVAL * 1000)
        val teamTopTool = intervalTeamTopTool[internal] ?: continue // time out of range
        val tool = teamTopTool["team${submission.teamId}"] ?: Tool.Unknown
        val subCount = langToolCountSubmitted.getOrPut(submission.language) { IntArray(TOOLS_WITH_UNKNOWN.size) }
        subCount[tool.ordinal]++
        if (submission.accepted) {
            val accCount = langToolCountAccepted.getOrPut(submission.language) { IntArray(TOOLS_WITH_UNKNOWN.size) }
            accCount[tool.ordinal]++
        }
    }
    println("Writing $LANGS_SUBMITTED_FILE and $LANGS_ACCEPTED_FILE")
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

    // todo:
    println("Writing $TEAMS_NAME_FILE")
    scope {
        val out = writeFile(TEAMS_NAME_FILE)
        for (team in teams) {
            out.println(team)
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

fun DeferScope.writeFile(fileName: String): PrintWriter =
    File(fileName).printWriter().also { defer { it.close() } }