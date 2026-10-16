package com.nexastream.app.providers

import android.util.Log
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.models.*
import com.nexastream.app.providers.magis.MagisApiClient
import com.nexastream.app.providers.magis.SignO3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.regex.Pattern

object MagisProvider : IptvProvider {
    private const val TAG = "MagisProvider"

    override val name: String = "Magis TV Live & VOD"
    override val baseUrl: String = "https://niguof.vynbszicd.com"
    override val logo: String = "https://raw.githubusercontent.com/lordmacu/magia/main/docs/assets/magia-logo.svg"
    override val language: String = "es"

    private val apiClient = MagisApiClient()

    override suspend fun getHome(): List<Category> = withContext(Dispatchers.IO) {
        val categories = mutableListOf<Category>()
        apiClient.ensureActive()

        // 1. Fetch main live channels (columnId 76182 = "All Channels")
        runCatching {
            val liveData = apiClient.getLiveData("76182") ?: JSONObject()
            val channelListJson = liveData.optJSONArray("channelList")
                ?: liveData.optJSONArray("contentList")
                ?: liveData.optJSONArray("list")

            val mainChannels = mutableListOf<TvShow>()
            if (channelListJson != null) {
                for (i in 0 until channelListJson.length()) {
                    val item = channelListJson.optJSONObject(i) ?: continue
                    val code = item.optString("channelCode", item.optString("contentCode", item.optString("code")))
                    val name = item.optString("channelName", item.optString("contentName", item.optString("name", "Canal $code")))

                    var posterUrl = item.optString("poster", item.optString("icon", item.optString("logo")))
                    if (posterUrl.isEmpty()) {
                        val posterArray = item.optJSONArray("posterList")
                        if (posterArray != null && posterArray.length() > 0) {
                            posterUrl = posterArray.optJSONObject(0)?.optString("poster", "") ?: ""
                        }
                    }

                    if (code.isNotEmpty()) {
                        mainChannels.add(
                            TvShow(
                                id = "magis_live_$code",
                                title = name,
                                overview = "Magis TV Live Channel - $name",
                                poster = posterUrl.ifEmpty { logo },
                                banner = posterUrl.ifEmpty { logo }
                            )
                        )
                    }
                }
            }

            if (mainChannels.isNotEmpty()) {
                categories.add(
                    Category(
                        name = "Magis TV - All Channels",
                        list = mainChannels
                    ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
                )
            }
        }.onFailure { Log.e(TAG, "Error fetching main live channels", it) }

        // 2. Fetch live categories from liveCategories()
        runCatching {
            val categoriesResp = apiClient.liveCategories()
            val recommendList = categoriesResp?.optJSONArray("recommendList")
                ?: categoriesResp?.optJSONArray("list")
                ?: categoriesResp?.optJSONArray("nextColumns")

            if (recommendList != null) {
                for (c in 0 until recommendList.length()) {
                    val catObj = recommendList.optJSONObject(c) ?: continue
                    val colId = catObj.optString("columnId", catObj.optString("id"))
                    val colName = catObj.optString("columnName", catObj.optString("name", catObj.optString("columnTitle")))

                    if (colId.isNotEmpty() && colId != "76182" && colName.isNotEmpty()) {
                        val catData = apiClient.getLiveData(colId) ?: continue
                        val catChannelsJson = catData.optJSONArray("channelList") ?: catData.optJSONArray("contentList")
                        val catChannels = mutableListOf<TvShow>()

                        if (catChannelsJson != null) {
                            for (i in 0 until catChannelsJson.length()) {
                                val item = catChannelsJson.optJSONObject(i) ?: continue
                                val code = item.optString("channelCode", item.optString("contentCode", item.optString("code")))
                                val name = item.optString("channelName", item.optString("contentName", item.optString("name", "Canal $code")))

                                var posterUrl = item.optString("poster", item.optString("icon", item.optString("logo")))
                                if (posterUrl.isEmpty()) {
                                    val posterArray = item.optJSONArray("posterList")
                                    if (posterArray != null && posterArray.length() > 0) {
                                        posterUrl = posterArray.optJSONObject(0)?.optString("poster", "") ?: ""
                                    }
                                }

                                if (code.isNotEmpty()) {
                                    catChannels.add(
                                        TvShow(
                                            id = "magis_live_$code",
                                            title = name,
                                            overview = "Magis TV - $colName",
                                            poster = posterUrl.ifEmpty { logo },
                                            banner = posterUrl.ifEmpty { logo }
                                        )
                                    )
                                }
                            }
                        }

                        if (catChannels.isNotEmpty()) {
                            categories.add(
                                Category(
                                    name = "Magis - $colName",
                                    list = catChannels
                                ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
                            )
                        }
                    }
                }
            }
        }.onFailure { Log.e(TAG, "Error fetching live categories", it) }

        // 3. Fetch Popular Movies & TV Series (VOD)
        runCatching {
            val vodSearchResp = apiClient.search("a", page = 1, pageSize = 30)
            val vodItemsJson = vodSearchResp?.optJSONArray("searchItemList")
            val popularMovies = mutableListOf<Movie>()
            val popularSeries = mutableListOf<TvShow>()

            if (vodItemsJson != null) {
                for (g in 0 until vodItemsJson.length()) {
                    val groupObj = vodItemsJson.optJSONObject(g) ?: continue
                    val itemList = groupObj.optJSONArray("itemList") ?: continue
                    for (i in 0 until itemList.length()) {
                        val item = itemList.optJSONObject(i) ?: continue
                        val code = item.optString("contentId", item.optString("code"))
                        val name = item.optString("name", item.optString("title"))
                        val progType = item.optString("programType", "movie")

                        var posterUrl = item.optString("poster", item.optString("icon"))
                        if (posterUrl.isEmpty()) {
                            val posterArray = item.optJSONArray("posterList")
                            if (posterArray != null && posterArray.length() > 0) {
                                posterUrl = posterArray.optJSONObject(0)?.optString("poster", "") ?: ""
                            }
                        }

                        if (code.isNotEmpty() && name.isNotEmpty()) {
                            if (progType == "teleplay" || progType == "series" || progType == "variety") {
                                popularSeries.add(
                                    TvShow(
                                        id = "magis_series_$code",
                                        title = name,
                                        overview = item.optString("tags", "Magis TV Series"),
                                        poster = posterUrl.ifEmpty { logo },
                                        banner = posterUrl.ifEmpty { logo }
                                    )
                                )
                            } else {
                                popularMovies.add(
                                    Movie(
                                        id = "magis_movie_$code",
                                        title = name,
                                        overview = item.optString("tags", "Magis TV Movie"),
                                        poster = posterUrl.ifEmpty { logo },
                                        banner = posterUrl.ifEmpty { logo }
                                    )
                                )
                            }
                        }
                    }
                }
            }

            if (popularMovies.isNotEmpty()) {
                categories.add(
                    Category(
                        name = "Magis Movies",
                        list = popularMovies
                    ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
                )
            }

            if (popularSeries.isNotEmpty()) {
                categories.add(
                    Category(
                        name = "Magis Series",
                        list = popularSeries
                    ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
                )
            }
        }.onFailure { Log.e(TAG, "Error fetching VOD catalog", it) }

        categories
    }

    override suspend fun getMovie(id: String): Movie = withContext(Dispatchers.IO) {
        val cleanCode = id.removePrefix("magis_movie_")
        val detailObj = apiClient.getDetail(cleanCode, type = "1") ?: JSONObject()
        val assetData = detailObj.optJSONObject("assetData") ?: detailObj

        val title = assetData.optString("name", assetData.optString("title", "Pelicula $cleanCode"))
        val overview = assetData.optString("description", assetData.optString("introduction", "Magis Movie"))
        var posterUrl = assetData.optString("poster", assetData.optString("icon"))

        if (posterUrl.isEmpty()) {
            val posterArray = assetData.optJSONArray("posterList")
            if (posterArray != null && posterArray.length() > 0) {
                posterUrl = posterArray.optJSONObject(0)?.optString("poster", "") ?: ""
            }
        }

        Movie(
            id = id,
            title = title,
            overview = overview,
            poster = posterUrl.ifEmpty { logo },
            banner = posterUrl.ifEmpty { logo }
        )
    }

    override suspend fun getTvShow(id: String): TvShow = withContext(Dispatchers.IO) {
        val cleanCode = id.removePrefix("magis_series_")
        val detailObj = apiClient.getDetail(cleanCode, type = "0") ?: JSONObject()
        val assetData = detailObj.optJSONObject("assetData") ?: detailObj

        val title = assetData.optString("name", assetData.optString("title", "Serie $cleanCode"))
        val overview = assetData.optString("description", assetData.optString("introduction", "Magis Series"))
        var posterUrl = assetData.optString("poster", assetData.optString("icon"))

        if (posterUrl.isEmpty()) {
            val posterArray = assetData.optJSONArray("posterList")
            if (posterArray != null && posterArray.length() > 0) {
                posterUrl = posterArray.optJSONObject(0)?.optString("poster", "") ?: ""
            }
        }

        val episodes = mutableListOf<Episode>()
        val programList = assetData.optJSONArray("simpleProgramList")
        if (programList != null) {
            for (i in 0 until programList.length()) {
                val epObj = programList.optJSONObject(i) ?: continue
                val epCode = epObj.optString("contentId", epObj.optString("code"))
                val epName = epObj.optString("name", "Episodio ${i + 1}")
                val epNum = epObj.optInt("seriesNumber", i + 1)

                if (epCode.isNotEmpty()) {
                    episodes.add(
                        Episode(
                            id = "magis_ep_${epCode}_series_${cleanCode}",
                            number = epNum,
                            title = epName,
                            overview = "Episodio $epNum"
                        )
                    )
                }
            }
        }

        val seasons = if (episodes.isNotEmpty()) {
            listOf(
                Season(
                    id = "${id}_season_1",
                    number = 1,
                    title = "Temporada 1",
                    episodes = episodes
                )
            )
        } else emptyList()

        TvShow(
            id = id,
            title = title,
            overview = overview,
            poster = posterUrl.ifEmpty { logo },
            banner = posterUrl.ifEmpty { logo },
            seasons = seasons
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = emptyList()

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = withContext(Dispatchers.IO) {
        if (id.startsWith("magis_live_")) {
            val cleanCode = id.removePrefix("magis_live_")
            listOf(
                Video.Server(
                    id = id,
                    name = "Magis Live Stream",
                    src = cleanCode
                )
            )
        } else {
            listOf(
                Video.Server(
                    id = id,
                    name = "Magis VOD Stream",
                    src = id
                )
            )
        }
    }

    override suspend fun getVideo(server: Video.Server): Video = withContext(Dispatchers.IO) {
        val serverSrc = server.src

        if (serverSrc.startsWith("magis_live_") || server.id.startsWith("magis_live_")) {
            val channelCode = serverSrc.removePrefix("magis_live_")

            // 1. Get license via startPlayLive
            val playData = apiClient.startPlayLive(channelCode) ?: JSONObject()
            val addressList = playData.optJSONArray("liveAddressList")
            val firstAddrObj = addressList?.optJSONObject(0)
            val license = firstAddrObj?.optString("license", "") ?: ""

            // 2. Get SLB cfl endpoint
            val slbData = apiClient.getSlbInfo(channelCode) ?: JSONObject()
            val cdnList = slbData.optJSONArray("cdn_list")

            var cflHost = "niguof.vynbszicd.com"
            var cflAuthUrl = ""

            if (cdnList != null) {
                for (i in 0 until cdnList.length()) {
                    val cdnObj = cdnList.optJSONObject(i) ?: continue
                    val tag = cdnObj.optString("tag")
                    val mainAddr = cdnObj.optString("main_addr").replace("http://", "").replace("https://", "").split("/")[0]

                    val urlList = cdnObj.optJSONArray("url_list")
                    if (urlList != null) {
                        for (u in 0 until urlList.length()) {
                            val uObj = urlList.optJSONObject(u) ?: continue
                            val rawUrl = uObj.optString("url")
                            if (rawUrl.contains("sign_type=cfl") || tag == "live") {
                                if (mainAddr.isNotEmpty()) cflHost = mainAddr
                                cflAuthUrl = rawUrl
                                if (rawUrl.contains("sign_type=cfl")) break
                            }
                        }
                    }
                    if (cflAuthUrl.contains("sign_type=cfl")) break
                }
            }

            val tokenMatcher = Pattern.compile("token=([0-9A-Fa-f]{32})").matcher(cflAuthUrl)
            val token = if (tokenMatcher.find()) tokenMatcher.group(1) ?: "" else ""

            val authQuery = if (cflAuthUrl.contains("?")) cflAuthUrl.substringAfter("?") else cflAuthUrl
            val baseUrl = "https://$cflHost/live/$channelCode.m3u8"
            val fullBaseUrl = if (authQuery.isNotEmpty()) "$baseUrl?$authQuery" else baseUrl

            val nowMs = System.currentTimeMillis()
            val sign2Token = if (token.isNotEmpty()) SignO3.sign2(token, nowMs) else ""
            val delim = if (fullBaseUrl.contains("?")) "&" else "?"

            val finalStreamUrl = if (sign2Token.isNotEmpty()) {
                "$fullBaseUrl${delim}sign2_method=sign_o3&instance=0&start_moment=$nowMs&sign2=$sign2Token"
            } else {
                fullBaseUrl
            }

            Video(
                source = finalStreamUrl,
                headers = mapOf(
                    "Content-Auth" to authQuery,
                    "Content-License" to license,
                    "User-Agent" to "Ranger/4.9.4-17294ac0",
                    "App" to "com.android.msandroid",
                    "App-Version" to "49902"
                )
            )
        } else {
            // VOD Video playback (Movie or Episode)
            val cleanCode = serverSrc.removePrefix("magis_movie_")
                .removePrefix("magis_series_")
                .substringBefore("_series_")
                .removePrefix("magis_ep_")

            val seriesContentId = if (serverSrc.contains("_series_")) serverSrc.substringAfter("_series_") else ""

            // 1. Play VOD
            val playVodData = apiClient.playVod(cleanCode, seriesContentId = seriesContentId) ?: JSONObject()
            val epList = playVodData.optJSONArray("episodeList")
            val epObj = epList?.optJSONObject(0)
            val totalMovies = epObj?.optJSONArray("totalMovieList")
            val tmObj = totalMovies?.optJSONObject(0)
            val movieList = tmObj?.optJSONArray("movieList")
            val movieObj = movieList?.optJSONObject(0)

            val mediaCode = movieObj?.optString("contentId", cleanCode) ?: cleanCode
            val videoFormat = movieObj?.optString("videoFormat", "mp4") ?: "mp4"

            val licList = movieObj?.optJSONArray("licenseList")
            val license = licList?.optJSONObject(0)?.optString("license", "") ?: ""

            // 2. Get VOD SLB Cdn info
            val vodSlb = apiClient.getVodSlbInfo() ?: JSONObject()
            val cdnList = vodSlb.optJSONArray("cdn_list")

            var vodHost = "niguof.vynbszicd.com"
            var vodAuthUrl = ""

            if (cdnList != null) {
                for (i in 0 until cdnList.length()) {
                    val cdnObj = cdnList.optJSONObject(i) ?: continue
                    val tag = cdnObj.optString("tag")
                    if (tag == "vod") {
                        val mainAddr = cdnObj.optString("main_addr").replace("http://", "").replace("https://", "").split("/")[0]
                        if (mainAddr.isNotEmpty()) vodHost = mainAddr

                        val urlList = cdnObj.optJSONArray("url_list")
                        if (urlList != null) {
                            for (u in 0 until urlList.length()) {
                                val uObj = urlList.optJSONObject(u) ?: continue
                                val rawUrl = uObj.optString("url")
                                if (rawUrl.contains("sign_type=cfl")) {
                                    vodAuthUrl = rawUrl
                                    break
                                }
                            }
                        }
                        if (vodAuthUrl.isNotEmpty()) break
                    }
                }
            }

            val ext = if (videoFormat == "ts") "ts" else "mp4"
            val streamUrl = "https://$vodHost/vod/${mediaCode}_media.$ext"

            Video(
                source = streamUrl,
                headers = mapOf(
                    "Content-Auth" to vodAuthUrl,
                    "Content-License" to license,
                    "User-Agent" to "Ranger/4.9.4-17294ac0",
                    "App" to "com.android.msandroid",
                    "App-Version" to "49902"
                )
            )
        }
    }

    override suspend fun search(query: String, page: Int, filters: SearchFilters?): List<AppAdapter.Item> = withContext(Dispatchers.IO) {
        val results = mutableListOf<AppAdapter.Item>()
        runCatching {
            apiClient.ensureActive()
            val searchResp = apiClient.search(query, page)
            val itemsJson = searchResp?.optJSONArray("searchItemList")

            if (itemsJson != null) {
                for (g in 0 until itemsJson.length()) {
                    val groupObj = itemsJson.optJSONObject(g) ?: continue
                    val itemList = groupObj.optJSONArray("itemList") ?: continue
                    for (i in 0 until itemList.length()) {
                        val item = itemList.optJSONObject(i) ?: continue
                        val code = item.optString("contentId", item.optString("code"))
                        val name = item.optString("name", item.optString("title"))
                        val progType = item.optString("programType", "movie")

                        var posterUrl = item.optString("poster", item.optString("icon", logo))
                        if (posterUrl.isEmpty()) {
                            val posterArray = item.optJSONArray("posterList")
                            if (posterArray != null && posterArray.length() > 0) {
                                posterUrl = posterArray.optJSONObject(0)?.optString("poster", "") ?: ""
                            }
                        }

                        if (code.isNotEmpty() && name.isNotEmpty()) {
                            if (progType == "teleplay" || progType == "series" || progType == "variety") {
                                results.add(
                                    TvShow(
                                        id = "magis_series_$code",
                                        title = name,
                                        overview = item.optString("tags", "Magis Series"),
                                        poster = posterUrl.ifEmpty { logo },
                                        banner = posterUrl.ifEmpty { logo }
                                    )
                                )
                            } else {
                                results.add(
                                    Movie(
                                        id = "magis_movie_$code",
                                        title = name,
                                        overview = item.optString("tags", "Magis Movie"),
                                        poster = posterUrl.ifEmpty { logo },
                                        banner = posterUrl.ifEmpty { logo }
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }.onFailure {
            Log.e(TAG, "Error searching Magis", it)
        }
        results
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()
    override suspend fun getTvShows(page: Int): List<TvShow> = emptyList()
    override suspend fun getGenre(id: String, page: Int): Genre = Genre(id, "")
    override suspend fun getPeople(id: String, page: Int): People = People(id, "")
}
