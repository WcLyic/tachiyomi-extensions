package eu.kanade.tachiyomi.extension.all.fmreader

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import java.net.URLEncoder
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable

class FMReaderFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        LHTranslation(),
        KissLove(),
        ReadComicOnlineOrg(),
        HanaScan(),
        RawLH(),
        Manhwa18(),
        EighteenLHPlus(),
        MangaTR(),
        Comicastle(),
        Manhwa18Net(),
        Manhwa18NetRaw(),
        SayTruyen(),
        EpikManga(),
        ManhuaScan(),
        ManhwaSmut()
    )
}

/** For future sources: when testing and popularMangaRequest() returns a Jsoup error instead of results
 *  most likely the fix is to override popularMangaNextPageSelector()   */

class LHTranslation : FMReader("LHTranslation", "https://lhtranslation.net", "en")

class KissLove : FMReader("KissLove", "https://kisslove.net", "ja") {
    override fun pageListParse(document: Document): List<Page> = base64PageListParse(document)
}

class ReadComicOnlineOrg : FMReader("ReadComicOnline.org", "https://readcomiconline.org", "en") {
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor { requestIntercept(it) }
        .build()

    private fun requestIntercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        return if (response.headers("set-cookie").isNotEmpty()) {
            val body = FormBody.Builder()
                .add("dqh_firewall", URLEncoder.encode(request.url().toString().substringAfter(baseUrl), "utf-8"))
                .build()
            val cookie = response.headers("set-cookie")[0].split(" ")
                .filter { it.contains("__cfduid") || it.contains("PHPSESSID") }
                .joinToString("; ") { it.substringBefore(";") }
            headers.newBuilder().add("Cookie", cookie).build()
            client.newCall(POST(request.url().toString(), headers, body)).execute()
        } else {
            response
        }
    }

    override val requestPath = "comic-list.html"
    override fun pageListParse(document: Document): List<Page> {
        val pages = document.select("div#divImage > select:first-of-type option").mapIndexed { i, imgPage ->
            Page(i, imgPage.attr("value"))
        }
        return pages.dropLast(1) // last page is a comments page
    }

    override fun imageUrlRequest(page: Page): Request = GET(baseUrl + page.url, headers)
    override fun imageUrlParse(document: Document): String = document.select("img.chapter-img").attr("abs:src").trim()
    override fun getGenreList() = getComicsGenreList()
}

class HanaScan : FMReader("HanaScan (RawQQ)", "https://hanascan.com", "ja") {
    override fun popularMangaNextPageSelector() = "div.col-md-8 button"
    // Referer needs to be chapter URL
    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headersBuilder().set("Referer", page.url).build())
}

class RawLH : FMReader("RawLH", "https://loveheaven.net", "ja") {
    override fun popularMangaNextPageSelector() = "div.col-md-8 button"
    override fun pageListParse(document: Document): List<Page> = base64PageListParse(document)
    // Referer needs to be chapter URL
    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headersBuilder().set("Referer", page.url).build())
}

class Manhwa18 : FMReader("Manhwa18", "https://manhwa18.com", "en") {
    override fun imageRequest(page: Page): Request {
        return if (page.imageUrl!!.contains("manhwa18")) {
            super.imageRequest(page)
        } else {
            GET(page.imageUrl!!, headers.newBuilder().removeAll("Referer").build())
        }
    }
    override fun getGenreList() = getAdultGenreList()
}

class EighteenLHPlus : FMReader("18LHPlus", "https://18lhplus.com", "en") {
    override fun popularMangaNextPageSelector() = "div.col-lg-8 div.btn-group:first-of-type"
    override fun getGenreList() = getAdultGenreList()
}

class MangaTR : FMReader("Manga-TR", "https://manga-tr.com", "tr") {
    override fun popularMangaNextPageSelector() = "div.btn-group:not(div.btn-block) button.btn-info"
    // TODO: genre search possible but a bit of a pain
    override fun getFilterList() = FilterList()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/arama.html?icerik=$query", headers)
    override fun searchMangaParse(response: Response): MangasPage {
        val mangas = mutableListOf<SManga>()

        response.asJsoup().select("div.row a[data-toggle]")
            .filterNot { it.siblingElements().text().contains("Novel") }
            .map { mangas.add(searchMangaFromElement(it)) }

        return MangasPage(mangas, false)
    }

    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        manga.setUrlWithoutDomain(element.attr("abs:href"))
        manga.title = element.text()

        return manga
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        val infoElement = document.select("div#tab1").first()

        manga.author = infoElement.select("table + table tr + tr td a").first()?.text()
        manga.artist = infoElement.select("table + table tr + tr td + td a").first()?.text()
        manga.genre = infoElement.select("div#tab1 table + table tr + tr td + td + td").text()
        manga.status = parseStatus(infoElement.select("div#tab1 table tr + tr td a").first().text())
        manga.description = infoElement.select("div.well").text().trim()
        manga.thumbnail_url = document.select("img.thumbnail").attr("abs:src")

        return manga
    }

    override fun chapterListSelector() = "tr.table-bordered"
    override val chapterUrlSelector = "td[align=left] > a"
    override val chapterTimeSelector = "td[align=right]"
    private val chapterListHeaders = headers.newBuilder().add("X-Requested-With", "XMLHttpRequest").build()
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val requestUrl = "$baseUrl/cek/fetch_pages_manga.php?manga_cek=${manga.url.substringAfter("manga-").substringBefore(".")}"
        return client.newCall(GET(requestUrl, chapterListHeaders))
            .asObservableSuccess()
            .map { response ->
                chapterListParse(response, requestUrl)
            }
    }

    private fun chapterListParse(response: Response, requestUrl: String): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var document = response.asJsoup()
        var moreChapters = true
        var nextPage = 2

        // chapters are paginated
        while (moreChapters) {
            document.select(chapterListSelector()).map { chapters.add(chapterFromElement(it)) }
            if (document.select("a[data-page=$nextPage]").isNotEmpty()) {
                val body = FormBody.Builder()
                    .add("page", nextPage.toString())
                    .build()
                document = client.newCall(POST(requestUrl, chapterListHeaders, body)).execute().asJsoup()
                nextPage++
            } else {
                moreChapters = false
            }
        }
        return chapters
    }

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl/${chapter.url.substringAfter("cek/")}", headers)
}

class Comicastle : FMReader("Comicastle", "https://www.comicastle.org", "en") {
    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/comic-dir?sorting=views&c-page=$page&sorting-type=DESC", headers)
    override fun popularMangaNextPageSelector() = "li:contains(»):not(.disabled)"
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/comic-dir?sorting=lastUpdate&c-page=$page&sorting-type=ASC", headers)
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/comic-dir?q=$query" + if (page > 1) "&c-page=$page" else "", headers)
    override fun getFilterList() = FilterList()
    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        val infoElement = document.select("div.col-md-9").first()

        manga.author = infoElement.select("tr + tr td a").first().text()
        manga.artist = infoElement.select("tr + tr td + td a").text()
        manga.genre = infoElement.select("tr + tr td + td + td").text()
        manga.description = infoElement.select("p").text().trim()
        manga.thumbnail_url = document.select("img.manga-cover").attr("abs:src")

        return manga
    }

    override fun chapterListSelector() = "div.col-md-9 table:last-of-type tr"
    override fun chapterListParse(response: Response): List<SChapter> = super.chapterListParse(response).reversed()
    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()

        document.select("div.text-center select option").forEachIndexed { i, imgPage ->
            pages.add(Page(i, imgPage.attr("value")))
        }
        return pages
    }

    override fun imageUrlParse(document: Document): String = document.select("img.chapter-img").attr("abs:src").trim()
    override fun getGenreList() = getComicsGenreList()
}

class Manhwa18Net : FMReader("Manhwa18.net", "https://manhwa18.net", "en") {
    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/$requestPath?listType=pagination&page=$page&sort=views&sort_type=DESC&ungenre=raw", headers)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/$requestPath?listType=pagination&page=$page&sort=last_update&sort_type=DESC&ungenre=raw", headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val noRawsUrl = super.searchMangaRequest(page, query, filters).url().newBuilder().addQueryParameter("ungenre", "raw").toString()
        return GET(noRawsUrl, headers)
    }

    override fun getGenreList() = getAdultGenreList()
}

class Manhwa18NetRaw : FMReader("Manhwa18.net Raw", "https://manhwa18.net", "ko") {
    override val requestPath = "manga-list-genre-raw.html"
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val onlyRawsUrl = super.searchMangaRequest(page, query, filters).url().newBuilder().addQueryParameter("genre", "raw").toString()
        return GET(onlyRawsUrl, headers)
    }

    override fun getFilterList() = FilterList(super.getFilterList().filterNot { it == GenreList(getGenreList()) })
}

class SayTruyen : FMReader("Say Truyen", "https://saytruyen.com", "vi") {
    override fun mangaDetailsParse(document: Document): SManga {
        val info = document.select("div.row").first()
        return SManga.create().apply {
            author = info.select("div.row li:has(b:contains(Tác giả)) small").text()
            genre = info.select("div.row li:has(b:contains(Thể loại)) small a").joinToString { it.text() }
            status = parseStatus(info.select("div.row li:has(b:contains(Tình trạng)) a").text())
            description = document.select("div.description").text()
            thumbnail_url = info.select("img.thumbnail").attr("abs:src")
        }
    }
    override fun chapterListParse(response: Response): List<SChapter> {
        return response.asJsoup().let { document ->
            document.select(chapterListSelector()).map { chapterFromElement(it).apply {
                scanlator = document.select("div.row li:has(b:contains(Nhóm dịch)) small").text()
            } }
        }
    }
    override fun pageListParse(document: Document): List<Page> = super.pageListParse(document).onEach { it.imageUrl!!.trim() }
}

class EpikManga : FMReader("Epik Manga", "https://www.epikmanga.com", "tr") {
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/seri-listesi?sorting=views&sorting-type=DESC&Sayfa=$page", headers)
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/seri-listesi?sorting=lastUpdate&sorting-type=DESC&Sayfa=$page", headers)
    override fun popularMangaNextPageSelector() = "ul.pagination li.active + li:not(.disabled)"
    // search wasn't working on source's website
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return client.newCall(searchMangaRequest(page, query, filters))
            .asObservableSuccess()
            .map { response ->
                searchMangaParse(response, query)
            }
    }
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/seri-listesi?type=text", headers)
    private fun searchMangaParse(response: Response, query: String): MangasPage {
        val mangas = response.asJsoup().select("div.char.col-lg-4 a")
            .filter { it.text().contains(query, ignoreCase = true) }
            .map {
                SManga.create().apply {
                    setUrlWithoutDomain(it.attr("href"))
                    title = it.text()
                }
            }
        return MangasPage(mangas, false)
    }
    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select("div.col-md-9 div.row").first()

        return SManga.create().apply {
            status = parseStatus(infoElement.select("h4:contains(Durum:)").firstOrNull()?.ownText())
            author = infoElement.select("h4:contains(Yazar:)").firstOrNull()?.ownText()
            artist = infoElement.select("h4:contains(Çizer:)").firstOrNull()?.ownText()
            genre = infoElement.select("h4:contains(Türler:) a").joinToString { it.text() }
            thumbnail_url = infoElement.select("img.thumbnail").imgAttr()
            description = document.select("div.col-md-12 p").text()
        }
    }
    override fun chapterListSelector() = "table.table tbody tr"
    override fun getFilterList(): FilterList = FilterList()
}

class ManhuaScan : FMReader("ManhuaScan", "https://manhuascan.com", "en")

class ManhwaSmut : FMReader("ManhwaSmut", "https://manhwasmut.com", "en")
