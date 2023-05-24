import java.io.*
import java.text.*
import java.util.*
import java.util.zip.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.*

val TEAMS_NAME_FILE = "icpc-teams-name.csv"
val LANGS_SUBMITTED_FILE = "icpc-langs-submitted.csv"
val LANGS_ACCEPTED_FILE = "icpc-langs-accepted.csv"

val PREF = "tool_data."
val SUFF = ".txt"
val WS = Regex("\\s+")

val INTERVAL = 5 * 60 // 5 min

val CMD_DROP_PREFIXES = listOf("/bin/sh", "/bin/bash", "sh", "bash")

enum class Tool(vararg val paths: String) {
    CLion("/opt/clion", "/usr/bin/clion", "clion"),
    Idea("/usr/lib/idea", "idea"),
    Pycharm("/usr/lib/pycharm", "pycharm"),
    Eclipse("/usr/lib/eclipse", "/usr/bin/java -Dosgi.requiredJavaVersion=1.8", "eclipse"),
    CodeBlocks("/usr/bin/codeblocks", "codeblocks"),
    Geany("/usr/bin/geany", "geany"),
    Emacs("/usr/bin/emacs", "emacs"),
    GEdit("/usr/bin/gedit", "gedit"),
    Vim("/usr/bin/vim", "vim", "gvim"),
    Vi("/usr/bin/vi", "vi"),
    VSCode("/usr/share/code", "/usr/bin/code", "vscode"),
// --- Not actually used anymore: --
//    Kate("/usr/bin/kate", "kate"),
//    Nano("nano"),
    Unknown // must be last
}

val TOOLS = Tool.values().filter { it != Tool.Unknown }
val TOOLS_WITH_UNKNOWN = Tool.values().toList()

class Usage(val time: Long, val tool: Tool, val cpu: Double)

data class TeamComputer(val team: String, val computer: Char) {
    override fun toString(): String = team + computer
}

fun ZipEntry.teamComputerOrNull(): TeamComputer? {
    val n = name.substring(name.lastIndexOf('/') + 1)
    return if (n.startsWith(PREF) && n.endsWith(SUFF)) {
        val tc = n.substring(PREF.length, n.length - SUFF.length)
        TeamComputer(tc.dropLast(1).padStart(3, '0'), tc.last())
    } else null
}

fun main(args: Array<String>) {
    if (args.size != 2) {
        println("Usage: Analyze <event-feed-json> <tool-backup-zip>")
        println(" <event-feed-json> -- JSON file with event feed")
        println(" <tool-backup-zip> -- ZIP file with 'tool_data.<team>[abc].txt' files")
        return
    }
    val eventFeedFile = args[0]
    val toolBackupFile = args[1]

    val teamComputerUsage = LinkedHashMap<TeamComputer, ArrayList<Usage>>()

    val zipFile = ZipFile(toolBackupFile)
    val groupedEntries = zipFile.entries().toList()
        .filter { ze -> !ze.isDirectory }
        .sortedBy { ze -> ze.name }
        .mapNotNull { ze -> ze.teamComputerOrNull()?.let { it to ze!! } }
        .groupBy({ it.first }, { it.second })
        .toList()
        .sortedBy { it.first.computer }
        .sortedBy { it.first.team }
    val unknownCommands = TreeMap<String, String>()
    val foundToolUsage = HashSet<Tool>()
    for ((tc, entries) in groupedEntries) {
        var time = 0L
//        teams += team
        val usage = ArrayList<Usage>()
        teamComputerUsage[tc] = usage
        val toolCpu = DoubleArray(TOOLS.size)

        fun flushUsage() {
            for (tool in TOOLS) {
                val value = toolCpu[tool.ordinal]
                if (value != 0.0) {
                    usage += Usage(time, tool, value)
                    toolCpu[tool.ordinal] = 0.0
                }
            }
        }

        println("Parsing $tc")
        for (ze in entries) {
            zipFile.getInputStream(ze).bufferedReader().useLines { lines ->
                for (line in lines) {
                    val curTime = line.toLongOrNull()
                    if (curTime != null) {
                        flushUsage()
                        time = curTime
                        continue
                    }
                    // USER PID %CPU %MEM VSZ RSS TTY STAT START TIME COMMAND
                    // 0    1   2    3    4   5   6   7    8     9    10
                    val tokens = line.split(WS, 11)
                    val cmdRaw = tokens.getOrNull(10) ?: continue
                    val cmd = cmdRaw.split(" ")
                        .dropWhile { it in CMD_DROP_PREFIXES }
                        .takeWhile { part ->
                            !part.startsWith("/home") &&
                            !part.startsWith("/tmp") &&
                            !part.startsWith("/var/tmp") &&
                            !part.startsWith("/run/user") &&
                            !part.startsWith("--field-trial-handle=") &&
                            !part.startsWith("./")
                        }
                        .joinToString(" ")
                        .takeIf { it.isNotEmpty() } ?: continue
                    val tool = TOOLS.firstOrNull { it.paths.any { cmd.startsWith(it) } }
                    if (tool == null) {
                        if (cmd !in unknownCommands) unknownCommands[cmd] = ze.name
                    } else {
                        if (foundToolUsage.add(tool)) println("Found tool usage: $tool")
                        val cpu = tokens[2].toDoubleOrNull() ?: continue
                        toolCpu[tool.ordinal] =
                            max(toolCpu[tool.ordinal] + cpu, 0.01) // make it always non-zero on usage
                    }
                }
            }
        }
        flushUsage()
    }

    // dump all unknown commands seen
    scope {
        val cmdOut = writeFile("icpc-unknown-command-lines.csv")
        cmdOut.println("CMD,FILE")
        for ((cmd, file) in unknownCommands) {
            cmdOut.println("$cmd,$file")
        }
    }

    // write
    val intervalTeamComputerTopTool = mutableMapOf<Long, Map<TeamComputer, Tool>>()
    val teamComputers = teamComputerUsage.keys
    scope {
        val toolsOut = writeFile("icpc-tools.csv")
        val teamsOut = writeFile("icpc-team-computers-time.csv")
        toolsOut.println("TIME,${TOOLS.joinToString(",")}")
        teamsOut.println("TIME,${teamComputers.joinToString(",")}")
        var curInterval = 0L
        val teamTools = HashMap<TeamComputer, DoubleArray>()

        fun flushInterval() {
            val topTool = teamTools.mapNotNull { e ->
                val i = e.value.withIndex().maxBy { it.value }!!
                e.value.fill(0.0)
                if (i.value == 0.0) null else e.key to TOOLS[i.index]
            }.toMap()
            intervalTeamComputerTopTool[curInterval] = topTool
            val counts = topTool.values.groupingBy { it }.eachCount()
            if (counts.isEmpty()) return
            val time = SimpleDateFormat("HH:mm").format(curInterval * INTERVAL * 1000)
            toolsOut.println("$time,${TOOLS.joinToString(",") { (counts[it] ?: 0).toString() }}")
            teamsOut.println("$time,${teamComputers.joinToString(",") { topTool[it]?.name ?: "--" }}")
        }

        val usage = teamComputerUsage.toList()
            .flatMap { (tc, list) -> list.map { tc to it } }
            .sortedBy { it.second.time }
        for ((tc, u) in usage) {
            val interval = u.time / INTERVAL
            if (interval != curInterval) {
                flushInterval()
                curInterval = interval
            }
            val a = teamTools.getOrPut(tc) { DoubleArray(TOOLS.size) }
            a[u.tool.ordinal] += u.cpu
        }
        flushInterval()
    }

/*
    // analyze accepted runs
    val eventFeed = EventFeed(eventFeedFile)
    val langToolCountSubmitted = HashMap<Language, IntArray>()
    val langToolCountAccepted = HashMap<Language, IntArray>()
    for (submission in eventFeed.submissions.values) {
        val internal = submission.time / (INTERVAL * 1000)
        val teamTopTool = intervalTeamComputerTopTool[internal] ?: continue // time out of range
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
*/
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
    File(fileName).printWriter().also { defer { it.close() } }.also {
        println("Writing $fileName")
    }