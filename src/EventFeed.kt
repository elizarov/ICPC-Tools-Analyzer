import com.google.gson.*
import java.io.*
import java.text.*


class EventFeed(fileName: String) {
    private val file = File(fileName)
    private val teamProblem = HashSet<Pair<String, String>>()

    val submissions = mutableMapOf<String, Submission>()
    val teams = mutableMapOf<String, String>()

    init {
        println("Reading $file")
        val parser = JsonParser()
        var nAccepted = 0
        file.forEachLine l@{ line ->
            val obj = parser.parse(line) as? JsonObject ?: return@l
            val type = obj["type"]?.asString
            val op = obj["op"]?.asString
            val data = obj["data"].asJsonObject
            when (type) {
                "teams" -> {
                    // {"id":"278881","type":"teams","op":"create",
                    //     "data":{"externalid":"404709","group_ids":["13858"],
                    //     "affiliation":"University of Ni\u0161 - Faculty of Sciences and Mathematics",
                    //     "id":"118",
                    //     "icpc_id":"404709",
                    //     "name":"University of Ni\u0161 - Faculty of Sciences and Mathematics",
                    //     "organization_id":"4234","members":null},
                    //     "time":"2019-04-03T18:55:18.392+01:00"
                    // }
                    if (op != "create") return@l
                    val id = data["id"].asString
                    val name = data["name"].asString
                    teams[id] = name
                }
                "submissions" -> {
                    // {"id":"316201","type":"submissions","op":"create",
                    //   "data":{"language_id":"cpp","time":"2019-04-04T15:21:39.034+01:00",
                    //   "contest_time":"3:31:14.034","id":"11769","externalid":null,"team_id":"75",
                    //   "problem_id":"circular","entry_point":null,
                    //   "files":[{"href":"contests/finals/submissions/11769/files","mime":"application/zip"}]},
                    //   "time":"2019-04-04T15:21:39.044+01:00"
                    // }
                    if (op != "create") return@l
                    val id = data["id"].asString
                    val languageId = data["language_id"].asString
                    val language = LANGUAGES.firstOrNull { languageId.startsWith(it.prefix) } ?: return@l
                    submissions[id] = Submission(
                        id = id,
                        teamId = data["team_id"].asString,
                        problemId = data["problem_id"].asString,
                        language = language,
                        time = data["time"].asString.timeToLong()
                    )
                }
                "judgements" -> {
                    // {"id":"316202","type":"judgements","op":"create",
                    //   "data":{"max_run_time":null,"start_time":"2019-04-04T15:21:39.257+01:00",
                    //   "start_contest_time":"3:31:14.257","end_time":null,"end_contest_time":null,
                    //   "id":"18469","submission_id":"11769","valid":true,
                    //   "judgehost":"domjudge-judgehost3-0","judgement_type_id":null},
                    //   "time":"2019-04-04T15:21:39.263+01:00"}
                    // {"id":"316220","type":"judgements","op":"update",
                    //   "data":{"max_run_time":0.148,"start_time":"2019-04-04T15:21:39.257+01:00",
                    //   "start_contest_time":"3:31:14.257","end_time":"2019-04-04T15:21:44.707+01:00","end_contest_time":"3:31:19.707",
                    //   "id":"18469","submission_id":"11769","valid":true,
                    //   "judgehost":"domjudge-judgehost3-0","judgement_type_id":"WA"},
                    //   "time":"2019-04-04T15:21:44.718+01:00"
                    // }
                    val verdict = data["judgement_type_id"]
                    if (verdict !is JsonPrimitive || verdict.asString != "AC") return@l
                    val submissionId = data["submission_id"].asString
                    val submission = submissions[submissionId] ?: return@l
                    val tp = submission.teamId to submission.problemId
                    if (teamProblem.add(tp)) {
                        submission.accepted = true
                        nAccepted++
                    }
                }
            }
        }
        println("${submissions.size} submissions found, $nAccepted accepted")
    }
}

// 2019-04-04T15:21:39.044+01:00
private val TF: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

private fun String.timeToLong(): Long = TF.parse(this).time

data class Submission(
    val id: String,
    val teamId: String,
    val problemId: String,
    val language: Language,
    val time: Long,
    var accepted: Boolean = false
)

enum class Language(val prefix: String) {
    C("c"),
    Java("java"),
    Kotlin("kotlin"),
    Python("python")
}

val LANGUAGES = Language.values().toList()

fun main(args: Array<String>) {
    EventFeed(args[0])
}