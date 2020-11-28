package eu.kanade.tachiyomi.extension.ar.teamx

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

class TeamX : ParsedHttpSource() {

    override val name = "Team X"

    override val baseUrl = "https://team1x1.com"

    override val lang = "ar"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Popular

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/manga/" + if (page > 1) "page/$page/" else "", headers)
    }

    override fun popularMangaSelector() = "div.last-post-manga"

    private val titleSelector = "h3 a"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            title = element.select(titleSelector).text()
            setUrlWithoutDomain(element.select("a").first().attr("href"))
            thumbnail_url = element.select("img").let {
                if (it.hasAttr("data-src"))
                    it.attr("abs:data-src") else it.attr("abs:src")
            }
        }
    }

    override fun popularMangaNextPageSelector() = "i.fa-chevron-left"

    // Latest

    private val titlesAdded = mutableSetOf<String>()

    override fun latestUpdatesRequest(page: Int): Request {
        if (page == 1) titlesAdded.clear()

        return GET("$baseUrl/احدث-الفصول/" + if (page > 1) "page/$page/" else "", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select(latestUpdatesSelector())
            .distinctBy { it.select(titleSelector).text() }.let { elements ->
                if (titlesAdded.isNotEmpty()) elements.filter { it.select(titleSelector).text() !in titlesAdded } else elements
            }
            .map {
                titlesAdded.add(it.select(titleSelector).text())
                latestUpdatesFromElement(it)
            }

        return MangasPage(mangas, document.select(latestUpdatesNextPageSelector()).isNotEmpty())
    }

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (page == 1) titlesAdded.clear()

        return GET("$baseUrl/" + (if (page > 1) "page/$page/" else "") + "?s=$query", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = latestUpdatesParse(response)

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // Details

    override fun mangaDetailsParse(document: Document): SManga {
        return document.select("div.single-manga > div.container > div.row").let { info ->
            SManga.create().apply {
                title = info.select("div.col-md-9").text()
                description = info.select("div.story p").text()
                genre = info.select("div.genre a").joinToString { it.text() }
                thumbnail_url = info.select("img").let {
                    if (it.hasAttr("data-src"))
                        it.attr("abs:data-src") else it.attr("abs:src")
                }
            }
        }
    }

    // Chapters
    private fun chapterNextPageSelector() = "span.nextx_text a:contains(»)"

    override fun chapterListParse(response: Response): List<SChapter> {
        val allChapters = mutableListOf<SChapter>()
        var document = response.asJsoup()

        while (true) {
            val pageChapters = document.select(chapterListSelector()).map { chapterFromElement(it) }
            if (pageChapters.isEmpty())
                break

            allChapters += pageChapters

            val hasNextPage = document.select(chapterNextPageSelector()).isNotEmpty()
            if (!hasNextPage)
                break

            val nextUrl = document.select(chapterNextPageSelector()).attr("href")
            document = client.newCall(GET(nextUrl, headers)).execute().asJsoup()
        }

        return allChapters
    }

    // Filter out the fake chapters
    override fun chapterListSelector() = "div.single-manga-chapter div.col-md-12 a[href^=$baseUrl]"

    override fun chapterFromElement(element: Element): SChapter {
        return SChapter.create().apply {
            name = element.text()
            setUrlWithoutDomain(element.attr("href"))
        }
    }

    // Pages

    override fun pageListParse(document: Document): List<Page> {
        return document.select("div#translationPageall embed").mapIndexed { i, img ->
            Page(
                i,
                "",
                img.let {
                    if (it.hasAttr("data-src"))
                        it.attr("abs:data-src") else it.attr("abs:src")
                }
            )
        }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")
}
