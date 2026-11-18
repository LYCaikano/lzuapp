package li.lyc.lzuapp.ui.apps

data class AppCatalogEntry(val id: String, val title: String)

object AppCatalog {
    val entries: List<AppCatalogEntry> = listOf(
        AppCatalogEntry("eval", "学生评教"),
        AppCatalogEntry("dorm", "查寝"),
        AppCatalogEntry("net", "校园网"),
        AppCatalogEntry("grade", "成绩查询"),
        AppCatalogEntry("elec", "宿舍电费"),
        AppCatalogEntry("mail", "邮箱"),
    )

    fun normalizeOrder(stored: List<String>): List<String> {
        val knownIds = entries.mapTo(mutableSetOf()) { it.id }
        val normalized = stored.distinct().filter(knownIds::contains)
        return normalized + entries.map { it.id }.filterNot(normalized::contains)
    }
}
