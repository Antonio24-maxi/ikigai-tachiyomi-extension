package eu.kanade.tachiyomi.extension.es.ikigai

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.*
import okhttp3.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injectekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.*

class Ikigai : ConfigurableSource {

    override val name = "Ikigai"
    override val baseUrl = "https://lectorikigai.eltanews.com"
    override val lang = "es"
    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injectekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        .add("Referer", baseUrl)

    // ============================== Popular ===============================
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/?pagina=$page&orden=popular", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.series-item, div.manga-item").map { element ->
            popularMangaFromElement(element)
        }
        val hasNextPage = document.select("a.next-page, .pagination a[rel=next]").isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    private fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            title = element.select("h3 a, .series-title a, .manga-title").text()
            setUrlWithoutDomain(element.select("a").attr("href"))
            thumbnail_url = element.select("img").attr("abs:src")
            description = element.select(".series-summary, .manga-summary").text()
        }
    }

    // ============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/?pagina=$page&orden=updated", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ============================== Search ===============================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = HttpUrl.parse("$baseUrl/")!!.newBuilder()
        
        if (query.isNotEmpty()) {
            url.addQueryParameter("buscar", query)
        }
        
        url.addQueryParameter("pagina", page.toString())
        
        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("genero", filter.toUriPart())
                    }
                }
                is StatusFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("estado", filter.toUriPart())
                    }
                }
                is TypeFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("tipo", filter.toUriPart())
                    }
                }
                is SortFilter -> {
                    url.addQueryParameter("orden", filter.toUriPart())
                }
            }
        }
        
        return GET(url.toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ============================== Filters ===============================
    override fun getFilterList() = FilterList(
        Filter.Header("Usa los filtros para refinar tu búsqueda"),
        GenreFilter(),
        StatusFilter(), 
        TypeFilter(),
        SortFilter()
    )

    private class GenreFilter : UriPartFilter(
        "Género",
        arrayOf(
            Pair("Todos", ""),
            Pair("Acción", "accion"),
            Pair("Aventura", "aventura"),
            Pair("Comedia", "comedia"),
            Pair("Drama", "drama"),
            Pair("Fantasía", "fantasia"),
            Pair("Harem", "harem"),
            Pair("Horror", "horror"),
            Pair("Isekai", "isekai"),
            Pair("Josei", "josei"),
            Pair("Maduro", "maduro"),
            Pair("Psicológico", "psicologico"),
            Pair("Romance", "romance"),
            Pair("Sci-fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shounen", "shounen"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Tragedia", "tragedia"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri")
        )
    )

    private class StatusFilter : UriPartFilter(
        "Estado",
        arrayOf(
            Pair("Todos", ""),
            Pair("En emisión", "ongoing"),
            Pair("Completado", "completed"),
            Pair("Pausado", "hiatus"),
            Pair("Cancelado", "cancelled")
        )
    )

    private class TypeFilter : UriPartFilter(
        "Tipo",
        arrayOf(
            Pair("Todos", ""),
            Pair("Manga", "manga"),
            Pair("Manhwa", "manhwa"),
            Pair("Manhua", "manhua"),
            Pair("Novela", "novela"),
            Pair("One Shot", "one-shot")
        )
    )

    private class SortFilter : UriPartFilter(
        "Ordenar por",
        arrayOf(
            Pair("Más popular", "popular"),
            Pair("Más reciente", "updated"),
            Pair("Más visto", "views"),
            Pair("A-Z", "title"),
            Pair("Z-A", "title-reverse"),
            Pair("Más votado", "rating")
        )
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // ============================== Details ===============================
    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.select("h1.series-title, h1.manga-title").text()
            author = document.select(".series-author, .manga-author").text()
            artist = document.select(".series-artist, .manga-artist").text()
            status = parseStatus(document.select(".series-status, .manga-status").text())
            genre = document.select(".series-genres a, .manga-genres a").joinToString { it.text() }
            description = document.select(".series-summary, .manga-summary").text()
            thumbnail_url = document.select(".series-cover img, .manga-cover img").attr("abs:src")
        }
    }

    private fun parseStatus(status: String): Int = when {
        status.contains("En emisión", ignoreCase = true) -> SManga.ONGOING
        status.contains("Completado", ignoreCase = true) -> SManga.COMPLETED
        status.contains("Pausado", ignoreCase = true) -> SManga.ON_HIATUS
        status.contains("Cancelado", ignoreCase = true) -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    // ============================== Chapters ===============================
    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select(".chapter-list li, .chapters-list li").map { element ->
            SChapter.create().apply {
                name = element.select("a").text()
                setUrlWithoutDomain(element.select("a").attr("href"))
                date_upload = parseChapterDate(element.select(".chapter-date").text())
            }
        }.reversed() // Orden cronológico
    }

    private fun parseChapterDate(date: String): Long {
        return try {
            when {
                date.contains("hace") -> {
                    val now = Calendar.getInstance().timeInMillis
                    when {
                        date.contains("minuto") -> now - (date.filter { it.isDigit() }.toIntOrNull() ?: 0) * 60_000L
                        date.contains("hora") -> now - (date.filter { it.isDigit() }.toIntOrNull() ?: 0) * 3_600_000L
                        date.contains("día") -> now - (date.filter { it.isDigit() }.toIntOrNull() ?: 0) * 86_400_000L
                        else -> now
                    }
                }
                else -> {
                    val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                    dateFormat.parse(date)?.time ?: 0L
                }
            }
        } catch (e: Exception) {
            0L
        }
    }

    // ============================== Pages ===============================
    override fun pageListRequest(chapter: SChapter): Request {
        return GET(baseUrl + chapter.url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        
        // Para novelas
        val novelContent = document.select(".novel-content, .chapter-content")
        if (novelContent.isNotEmpty()) {
            return listOf(Page(0, "", createNovelImageUrl(novelContent.html())))
        }
        
        // Para mangas
        return document.select(".chapter-images img, .reading-content img").mapIndexed { index, element ->
            Page(index, "", element.attr("abs:src"))
        }
    }

    private fun createNovelImageUrl(content: String): String {
        // Crear una URL de datos para mostrar el contenido de la novela como imagen
        val encodedContent = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
        return "data:text/html;base64,$encodedContent"
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    // ============================== Preferences ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val nsfwPref = androidx.preference.CheckBoxPreference(screen.context).apply {
            key = "show_nsfw"
            title = "Mostrar contenido NSFW"
            summary = "Mostrar contenido para adultos"
            setDefaultValue(false)
        }
        
        val typePref = ListPreference(screen.context).apply {
            key = "preferred_type"
            title = "Tipo preferido"
            entries = arrayOf("Todos", "Solo manga", "Solo novelas")
            entryValues = arrayOf("all", "manga", "novel")
            setDefaultValue("all")
            summary = "%s"
        }
        
        screen.addPreference(nsfwPref)
        screen.addPreference(typePref)
    }

    // ============================== Utilities ===============================
    private fun Response.asJsoup(): Document = Jsoup.parse(body()!!.string())
}
