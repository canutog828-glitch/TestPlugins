package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.newExtractorLink

class ExampleProvider : MainAPI() {
    override var mainUrl = "https://lospobreflix.lat"
    override var name = "LosPobreFlix"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override var lang = "pt"
    override val hasMainPage = true

    private val interceptor = CloudflareKiller()

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get(request.data, interceptor = interceptor)
        val document = response.document
        val homeItems = mutableListOf<SearchResponse>()

        document.select("h3").forEach { h3 ->
            val a = h3.selectFirst("a") ?: return@forEach
            val title = a.text().takeIf { it.isNotEmpty() } ?: h3.attr("title")
            val url = a.attr("href")
            if (url.isEmpty()) return@forEach

            val parent = h3.parents().firstOrNull { p -> p.selectFirst("img") != null }
            val img = parent?.selectFirst("img")
            val posterRaw = img?.attr("data-src")?.takeIf { it.isNotEmpty() } ?: img?.attr("src")
            val poster = cleanImageUrl(posterRaw)

            val type = if (url.contains("/filme/")) TvType.Movie
            else if (url.contains("/serie/") || url.contains("/series/")) TvType.TvSeries
            else TvType.Movie

            if (type == TvType.TvSeries) {
                homeItems.add(newTvSeriesSearchResponse(title, url, type) {
                    this.posterUrl = poster
                })
            } else {
                homeItems.add(newMovieSearchResponse(title, url, type) {
                    this.posterUrl = poster
                })
            }
        }

        return newHomePageResponse(request.name, homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("$mainUrl/?s=$query", interceptor = interceptor)
        val document = response.document
        val searchResults = mutableListOf<SearchResponse>()

        document.select("h3").forEach { h3 ->
            val a = h3.selectFirst("a") ?: return@forEach
            val title = a.text().takeIf { it.isNotEmpty() } ?: h3.attr("title")
            val url = a.attr("href")
            if (url.isEmpty()) return@forEach

            val parent = h3.parents().firstOrNull { p -> p.selectFirst("img") != null }
            val img = parent?.selectFirst("img")
            val posterRaw = img?.attr("data-src")?.takeIf { it.isNotEmpty() } ?: img?.attr("src")
            val poster = cleanImageUrl(posterRaw)

            val type = if (url.contains("/filme/")) TvType.Movie
            else if (url.contains("/serie/") || url.contains("/series/")) TvType.TvSeries
            else TvType.Movie

            if (type == TvType.TvSeries) {
                searchResults.add(newTvSeriesSearchResponse(title, url, type) {
                    this.posterUrl = poster
                })
            } else {
                searchResults.add(newMovieSearchResponse(title, url, type) {
                    this.posterUrl = poster
                })
            }
        }

        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url, interceptor = interceptor)
        val document = response.document

        val title = document.selectFirst("h1.text-lead")?.text() ?: return null
        val playerContainer = document.selectFirst("#movie-player-container")

        val bannerRaw = playerContainer?.attr("data-backdrop")
            ?: document.selectFirst("img[alt*="backdrop"]")?.attr("data-src")
            ?: document.selectFirst("img[alt*="backdrop"]")?.attr("src")

        val banner = cleanImageUrl(bannerRaw)

        val posterRaw = document.selectFirst("img[alt*="poster"]")?.attr("data-src")
            ?: document.selectFirst("img[alt*="poster"]")?.attr("src")
            ?: bannerRaw

        val poster = cleanImageUrl(posterRaw)

        val plot = document.selectFirst("div.text-slate-700.dark\\:text-slate-200.md\\:text-lg p")?.text()
            ?: document.selectFirst("div.text-slate-700 p, div.text-slate-200 p")?.text()
            ?: document.selectFirst(".sinopse-text, p")?.text()
            ?: "Sem sinopse disponível."

        val apiContentId = playerContainer?.attr("data-apicontentid")?.trim().orEmpty()
        val playerType = playerContainer?.attr("data-playertype")?.trim()?.lowercase().orEmpty()
        val isSeries = url.contains("/serie/") || url.contains("/series/") || playerType == "episodio"

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val seasonBtns = document.select("a.seasonLoaderBtn")

            if (seasonBtns.isNotEmpty()) {
                for (btn in seasonBtns) {
                    val seasonNum = btn.attr("data-season").toIntOrNull() ?: 1
                    val seasonUrl = btn.attr("href")
                    if (seasonUrl.isEmpty()) continue

                    val seasonResponse = app.get(seasonUrl, interceptor = interceptor)
                    val seasonDoc = seasonResponse.document

                    val epLinks = seasonDoc.select("a[href*="/episodio/"]")
                    epLinks.forEach { epLink ->
                        val epUrl = epLink.attr("href")
                        if (epUrl.isEmpty()) return@forEach
                        val epName = epLink.text().trim()
                        val epNum = epName.filter { it.isDigit() }.toIntOrNull() ?: 1

                        episodes.add(newEpisode(epUrl) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = poster
                        })
                    }
                }
            } else {
                val epLinks = document.select("a[href*="/episodio/"]")
                epLinks.forEachIndexed { index, epLink ->
                    val epUrl = epLink.attr("href")
                    if (epUrl.isEmpty()) return@forEachIndexed
                    val epName = epLink.text().trim()
                    val epNum = epName.filter { it.isDigit() }.toIntOrNull() ?: (index + 1)

                    episodes.add(newEpisode(epUrl) {
                        this.name = epName
                        this.season = 1
                        this.episode = epNum
                        this.posterUrl = poster
                    })
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = banner
                this.plot = plot
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, apiContentId) {
            this.posterUrl = poster
            this.backgroundPosterUrl = banner
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, interceptor = interceptor)
        val document = response.document

        Log.d("CloudstreamPlugin", "URL solicitada: $data")

        val playerContainer = document.selectFirst("#movie-player-container")
        val apiContentId = playerContainer?.attr("data-apicontentid")?.trim().orEmpty()
        val playerType = playerContainer?.attr("data-playertype")?.trim()?.lowercase().orEmpty()

        val apiList = mutableListOf<String>()
        val apiScriptRegex = Regex("""window.__PLAYER_APIS__s*=s*[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
        val inside = apiScriptRegex.find(response.text)?.groupValues?.getOrNull(1).orEmpty()
        Regex(""""([^"]+)"""").findAll(inside).forEach {
            apiList.add(it.groupValues[1].trim())
        }

        Log.d("CloudstreamPlugin", "apiContentId: $apiContentId")
        Log.d("CloudstreamPlugin", "playerType: $playerType")
        Log.d("CloudstreamPlugin", "APIs encontradas: $apiList")

        if (apiList.isEmpty() || apiContentId.isEmpty()) return false

        var foundAny = false

        for (api in apiList) {
            val sourceUrl = if (playerType == "episodio") {
                val season = playerContainer?.attr("data-season")?.toIntOrNull() ?: 1
                val episode = playerContainer?.attr("data-episode")?.toIntOrNull() ?: 1
                "https://$api/serie/$apiContentId/$season/$episode"
            } else {
                "https://$api/filme/$apiContentId"
            }

            try {
                Log.d("CloudstreamPlugin", "Tentando sourceUrl: $sourceUrl")
                val loaded = loadExtractor(sourceUrl, data, subtitleCallback, callback)
                if (loaded) foundAny = true
            } catch (e: Exception) {
                Log.e("CloudstreamPlugin", "Erro ao carregar $sourceUrl", e)
            }
        }

        return foundAny
    }

    private fun cleanImageUrl(url: String?): String? {
        if (url == null) return null
        val secondHttpIndex = url.indexOf("http", 1)
        if (secondHttpIndex > 0) {
            return url.substring(secondHttpIndex)
        }
        return url
    }
              }
