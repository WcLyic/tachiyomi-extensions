package eu.kanade.tachiyomi.extension.zh.rouman5

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

class RouMan5 : HttpSource() {

    override val name = "肉漫屋"
    override val baseUrl = "https://rouman5.com"
    override val lang = "zh"
    override val supportsLatest = true

    private val booksPageSize = 24
    private val searchPageSize = 15

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/books?sort=rate&page=${(page - 1)}", headers)
    override fun popularMangaParse(response: Response): MangasPage = parseSearchMangaOrPopularOrLatestResponse(response)
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/books?sort=&page=${(page - 1)}", headers)
    override fun latestUpdatesParse(response: Response): MangasPage = parseSearchMangaOrPopularOrLatestResponse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request  {
        var searchUrlString = "$baseUrl/search?term=${query}&page=${(page - 1)}"
        var booksUrlString = "$baseUrl/books?page=${(page - 1)}"
        var requestUrlString: String

        val params = filters.map {
            if (it is MangaFilter) {
                it.toUriPart()
            } else ""
        }.filter { it != "" }.joinToString("&")
        // perform books search only when do have filter and not search anything
        if (params != "" && query == "") {
            requestUrlString = booksUrlString + "&$params"
        } else {
            requestUrlString = searchUrlString
        }
        val url = HttpUrl.parse(requestUrlString)?.newBuilder()
        return GET(url.toString(), headers)
    }
    override fun searchMangaParse(response: Response): MangasPage = parseSearchMangaOrPopularOrLatestResponse(response)

    private fun parseSearchMangaOrPopularOrLatestResponse(response: Response): MangasPage {
        val document = response.asJsoup()
        val booksJsonString = document.select("script#__NEXT_DATA__").html()
        val booksJson = JSONObject(booksJsonString)
        val pageProps = booksJson.getJSONObject("props").getJSONObject("pageProps")
        val hasNextPage = pageProps.getBoolean("hasNextPage")
        val booksArray = pageProps.getJSONArray("books")

        val ret = ArrayList<SManga>(booksArray.length())
        for (i in 0 until booksArray.length()) {
            val obj = booksArray.getJSONObject(i)
            ret.add(mangaFromBook(obj))
        }

        return MangasPage(ret, hasNextPage)
    }

    private fun mangaFromBook(obj: JSONObject): SManga {
        val manga = SManga.create().apply {
            url = "/books/${obj.getString("id")}"
            title = obj.getString("name")
            thumbnail_url = obj.getString("coverUrlTop")
            author = obj.getString("author")
            description = obj.getString("description")
            status = when (obj.optBoolean("continued")) {
                true -> SManga.ONGOING
                false -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
        val arr = obj.getJSONArray("tags")
        val tmparr = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            tmparr.add(arr.getString(i))
        }
        manga.genre = tmparr.joinToString(", ")
        return manga
    }

    override fun mangaDetailsRequest(manga: SManga) = GET(baseUrl + manga.url, headers)
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val mangaJsonString = document.select("script#__NEXT_DATA__").html()
        val mangaJson = JSONObject(mangaJsonString)
        val manga = mangaFromBook(mangaJson.getJSONObject("props").getJSONObject("pageProps").getJSONObject("book"))
        return manga
    }

    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val mangaJsonString = document.select("script#__NEXT_DATA__").html()
        val mangaJson = JSONObject(mangaJsonString)
        val bookJson = mangaJson.getJSONObject("props").getJSONObject("pageProps").getJSONObject("book")

        val id = bookJson.getString("id")
        val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX").parse(bookJson.getString("lastUpdate"))?.time ?: 0L
        val chapterArray = bookJson.getJSONArray("chapters")

        val ret = ArrayList<SChapter>()
        for (i in 0 until chapterArray.length()) {
            ret.add(
                SChapter.create().apply {
                    name = chapterArray.getString(i)
                    date_upload = time
                    url = "/books/$id/$i"
                }
            )
        }

        return ret.asReversed()
    }

    override fun pageListRequest(chapter: SChapter) = GET(baseUrl + chapter.url, headers)
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val pageJsonString = document.select("script#__NEXT_DATA__").html()
        val pageJson = JSONObject(pageJsonString)
        var pageArray = pageJson.getJSONObject("props").getJSONObject("pageProps").optJSONArray("images")

        if (pageArray == null) {
            val url = response.request().url().toString()
                .replace("/books/", "/api/books/")
            val obj = client.newCall(GET(url, headers)).execute().let { JSONObject(it.body()!!.string()) }
            pageArray = obj.getJSONObject("chapter").getJSONArray("images")
        }

        val ret = ArrayList<Page>(pageArray.length())
        for (i in 0 until pageArray.length()) {
            ret.add(Page(i, "", pageArray.getJSONObject(i).getString("src")))
        }

        return ret
    }

    override fun headersBuilder() = super.headersBuilder()
        // .add("Referer", baseUrl)
        .add("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/56.0.2924.87 Safari/537.36")

    // Unused, we can get image urls directly from the chapter page
    override fun imageUrlParse(response: Response) =
        throw UnsupportedOperationException("This method should not be called!")

    // Copymanga has different logic in polular and search page, mix two logic in search progress for now
    override fun getFilterList() = FilterList(
        MangaFilter(
            "標籤",
            "tag",
            arrayOf(
                Pair("全部", ""),
                Pair("正妹", "正妹"),
                Pair("恋爱", "恋爱"),
                Pair("出版漫画", "出版漫画"),
                Pair("肉慾", "肉慾"),
                Pair("浪漫", "浪漫"),
                Pair("大尺度", "大尺度"),
                Pair("巨乳", "巨乳"),
                Pair("有夫之婦", "有夫之婦"),
                Pair("女大生", "女大生"),
                Pair("狗血劇", "狗血劇"),
                Pair("同居", "同居"),
                Pair("好友", "好友"),
                Pair("調教", "調教"),
                Pair("动作", "动作"),
                Pair("後宮", "後宮"),
                Pair("搞笑", "搞笑")
            )
        ),
        MangaFilter(
            "狀態",
            "continued",
            arrayOf(
                Pair("全部", ""),
                Pair("連載中", "true"),
                Pair("已完結", "false")
            )
        ),
        MangaFilter(
            "排序",
            "sort",
            arrayOf(
                Pair("更新日期", ""),
                Pair("評分", "rate")
            )
        )
    )

    private class MangaFilter(
        displayName: String,
        searchName: String,
        val vals: Array<Pair<String, String>>,
        defaultValue: Int = 0
    ) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), defaultValue) {
        val searchName = searchName
        fun toUriPart(): String {
            val selectVal = vals[state].second
            return if (selectVal != "") "$searchName=$selectVal" else ""
        }
    }
}
