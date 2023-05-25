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
            val data = obj["data"].asJsonObject
            when (type) {
                "teams" -> {
                    // {
                    //  "token": "145511",
                    //  "id": "1",
                    //  "type": "teams",
                    //  "data": {
                    //    "organization_id": "7790",
                    //    "hidden": false,
                    //    "group_ids": [
                    //      "17106"
                    //    ],
                    //    "affiliation": "Adama Science and Technology University",
                    //    "id": "1",
                    //    "icpc_id": "697451",
                    //    "name": "Andalus",
                    //    "display_name": "Adama Science and Technology University",
                    //    "public_description": null,
                    //    "photo": [...]
                    //  },
                    //  "time": "2022-11-09T19:20:15.057+06:00"
                    // }
                    val id = data["id"].asString
                    val name = data["name"].asString
                    teams[id] = name
                }
                "submissions" -> {
                    // {
                    //  "token": "145665",
                    //  "id": "3506",
                    //  "type": "submissions",
                    //  "data": {
                    //    "language_id": "java",
                    //    "time": "2022-11-09T19:20:15.078+06:00",
                    //    "contest_time": "-15:39:44.921",
                    //    "team_id": "tobi",
                    //    "problem_id": "cross",
                    //    "id": "3506",
                    //    "external_id": null,
                    //    "entry_point": null,
                    //    "files": [...]
                    //  },
                    //  "time": "2022-11-09T19:20:15.090+06:00"
                    // }
                    val id = data["id"].asString
                    val languageId = data["language_id"].asString
                    val language = LANGUAGES.firstOrNull { languageId.startsWith(it.prefix) } ?: run {
                        println("!!! Unknown language: $languageId in run $id")
                        return@l
                    }
                    submissions[id] = Submission(
                        id = id,
                        teamId = data["team_id"].asString,
                        problemId = data["problem_id"].asString,
                        language = language,
                        time = data["time"].asString.timeToLong()
                    )
                }
                "judgements" -> {
                    // {
                    //  "token": "145983",
                    //  "id": "3663",
                    //  "type": "judgements",
                    //  "data": {
                    //    "max_run_time": 0.533,
                    //    "start_time": "2022-11-09T19:20:16.000+06:00",
                    //    "start_contest_time": "-15:39:43.999",
                    //    "end_time": "2022-11-09T19:20:23.805+06:00",
                    //    "end_contest_time": "-15:39:36.194",
                    //    "submission_id": "3508",
                    //    "id": "3663",
                    //    "valid": true,
                    //    "judgement_type_id": "AC"
                    //  },
                    //  "time": "2022-11-09T19:20:23.820+06:00"
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
        // report summary
        println("${teams.size} teams found")
        println("${submissions.size} submissions found, $nAccepted accepted")
        println("Team ids: ${teams.keys.sortedBy { it.teamIdSortKey() }}")
        val allTimes = submissions.values.map { it.time / 1000 }
        println("Submissions time range (seconds from epoch):")
        println("  Min: ${allTimes.min()}")
        println("  Max: ${allTimes.max()}")

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

fun String.teamIdSortKey() = padStart(3, '0')

fun main(args: Array<String>) {
    EventFeed(args[0])
}