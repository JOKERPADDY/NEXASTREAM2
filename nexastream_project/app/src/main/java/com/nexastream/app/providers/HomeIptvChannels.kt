package com.nexastream.app.providers

import com.nexastream.app.models.TvShow

object HomeIptvChannels {

    data class ChannelData(
        val id: String,
        val name: String,
        val url: String,
        val userAgent: String? = "Lavf/56.15.102",
        val group: String = "Sports",
        val logo: String? = null
    )

    val channels = listOf(
        // === SKY SPORTS UK ===
        ChannelData("sky-arena", "Sky Sports Arena HD", "http://ronaldo.tvfor.pro/uC3alP4H/tht6v98456np1k198n81n2t8", group = "Sky Sports"),
        ChannelData("sky-action", "Sky Sports Action HD", "http://ronaldo.tvfor.pro/fedegwwSe/tht6v98456np1k198n81n2t8", group = "Sky Sports"),
        ChannelData("sky-mix", "Sky Sports Mix HD", "http://ronaldo.tvfor.pro/VehBD92Q/tht6v98456np1k198n81n2t8", group = "Sky Sports"),
        ChannelData("sky-golf", "Sky Sports Golf HD", "http://ronaldo.tvfor.pro/alwFYkJK/tht6v98456np1k198n81n2t8", group = "Sky Sports"),
        ChannelData("sky-cricket", "Sky Sports Cricket HD", "http://ronaldo.tvfor.pro/cOwmjQnG/tht6v98456np1k198n81n2t8", group = "Sky Sports"),
        ChannelData("sky-f1", "Sky Sports F1 HD", "http://ronaldo.tvfor.pro/mMYFHBz8/tht6v98456np1k198n81n2t8", group = "Sky Sports"),
        ChannelData("sky-football", "Sky Sports Football HD", "http://ronaldo.tvfor.pro/LKGg4xhJ/tht6v98456np1k198n81n2t8", group = "Sky Sports"),
        ChannelData("sky-main-event", "Sky Sports Main Event HD", "http://ronaldo.tvfor.pro/zuatk8cz/tht6v98456np1k198n81n2t8", group = "Sky Sports"),
        ChannelData("sky-news", "Sky Sports News HD", "http://ronaldo.tvfor.pro/3IHoar5e/tht6v98456np1k198n81n2t8", group = "Sky Sports"),
        ChannelData("sky-racing", "Sky Sports Racing HD", "http://ronaldo.tvfor.pro/u9cU1TJZ/tht6v98456np1k198n81n2t8", group = "Sky Sports"),
        ChannelData("sky-premier-liga", "Sky Sport Premier Liga HD", "http://ronaldo.tvfor.pro/atLs7TPn/tht6v98456np1k198n81n2t8", group = "Sky Sports"),
        
        // === TNT SPORTS UK ===
        ChannelData("tnt-1", "TNT Sports 1 HD", "http://ronaldo.tvfor.pro/Z6V9d48eGz/tht6v98456np1k198n81n2t8", logo = "https://i.ibb.co/Z6V9d48eGz/tnt-sports-1.png", group = "TNT Sports"),
        ChannelData("tnt-2", "TNT Sports 2 HD", "http://ronaldo.tvfor.pro/3Z76Abnp8N/tht6v98456np1k198n81n2t8", logo = "https://i.ibb.co/3Z76Abnp8N/tnt-sports-2.png", group = "TNT Sports"),
        ChannelData("tnt-3", "TNT Sports 3 HD", "http://ronaldo.tvfor.pro/r25HTg8Sx7/tht6v98456np1k198n81n2t8", logo = "https://i.ibb.co/r25HTg8Sx7/tnt-sports-3.png", group = "TNT Sports"),
        ChannelData("tnt-4", "TNT Sports 4 HD", "http://ronaldo.tvfor.pro/Ff54kV22Cg/tht6v98456np1k198n81n2t8", logo = "https://i.ibb.co/Ff54kV22Cg/tnt-sports-4.png", group = "TNT Sports"),

        // === EUROSPORT ===
        ChannelData("eurosport-1", "Eurosport 1 HD", "http://ronaldo.tvfor.pro/EurosportHD/tht6v98456np1k198n81n2t8", logo = "https://i.ibb.co/EurosportHD/eurosport-1.png", group = "Eurosport"),
        ChannelData("eurosport-2", "Eurosport 2 HD", "http://ronaldo.tvfor.pro/Eurosport2HDRussia/tht6v98456np1k198n81n2t8", logo = "https://i.ibb.co/Eurosport2HD/eurosport-2.png", group = "Eurosport"),
        
        // === MATCH! SPORTS ===
        ChannelData("match-hd", "Матч! HD", "http://ronaldo.tvfor.pro/6kzDOOrtd1/tht6v98456np1k198n81n2t8", group = "Match!"),
        ChannelData("match-football-1-hd", "Матч! Футбол 1 HD", "http://ronaldo.tvfor.pro/MatchFutbol1HD/tht6v98456np1k198n81n2t8", group = "Match!"),
        ChannelData("match-football-2-hd", "Матч! Футбол 2 HD", "http://ronaldo.tvfor.pro/MatchFutbol2HD/tht6v98456np1k198n81n2t8", group = "Match!"),
        ChannelData("match-football-3-hd", "Матч! Футбол 3 HD", "http://ronaldo.tvfor.pro/MatchFutbol3HD/tht6v98456np1k198n81n2t8", group = "Match!"),
        ChannelData("match-premier-hd", "Матч Премьер HD", "http://ronaldo.tvfor.pro/NTVPlusNashFutbolHD/tht6v98456np1k198n81n2t8", group = "Match!"),
        
        // === BEIN SPORTS ===
        ChannelData("bein-1", "Bein Sports 1 HD", "http://ak.fly47.be:80/Emrullah20/X9J5VbPtxD/237081", userAgent = "Firefox", group = "Bein Sports", logo = "http://logo.multicms.info/logoslar/beinsports1.png"),
        ChannelData("bein-3", "Bein Sports 3 HD", "http://ak.fly47.be:80/Emrullah20/X9J5VbPtxD/31409", userAgent = "Firefox", group = "Bein Sports", logo = "http://logo.multicms.info/logoslar/beinsports3.png"),
        
        // === US SPORTS ===
        ChannelData("espn", "ESPN HD", "http://ronaldo.tvfor.pro/rEntionEOp/tht6v98456np1k198n81n2t8", group = "US Sports"),
        ChannelData("espn-2", "ESPN 2", "http://ronaldo.tvfor.pro/cRaHUGOBsT/tht6v98456np1k198n81n2t8", group = "US Sports"),
        ChannelData("fox-1", "Fox Sports 1 HD", "http://ronaldo.tvfor.pro/lOgencONiS/tht6v98456np1k198n81n2t8", group = "US Sports"),
        ChannelData("fox-2", "Fox Sports 2 HD", "http://ronaldo.tvfor.pro/quiCkTaNDE/tht6v98456np1k198n81n2t8", group = "US Sports"),
        
        // === GLOBAL TV ===
        ChannelData("bbc-news", "BBC News", "https://vs-hls-push-ww-live.akamaized.net/x=4/i=urn:bbc:pips:service:bbc_news_channel_hd/t=3840/v=pv14/b=5070016/main.m3u8", group = "Global TV", logo = "http://epg.one/img2/828.png"),
        ChannelData("cnn-intl", "CNN International", "https://ds2c506obo7m8.cloudfront.net/v1/master/3722c60a815c199d9c0ef36c5b73da68a62b09d1/cc-7zjq3tdqasbg8/index.m3u8", group = "Global TV", logo = "http://epg.one/img2/2270.png"),
        ChannelData("al-jazeera", "Al Jazeera HD", "http://live-hls-v3-aja.getaj.net/AJA-V3/index.m3u8", group = "Global TV", logo = "https://iptvx.one/picons/al-jazeera-int.png"),
        ChannelData("france24-en", "France 24 English", "https://live.france24.com/hls/live/2037218/F24_EN_HI_HLS/master_5000.m3u8", group = "Global TV", logo = "http://1lot.tv/ch/8163.png"),
        ChannelData("dw-en", "Deutsche Welle HD", "http://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/stream05/streamPlaylist.m3u8", group = "Global TV", logo = "http://1lot.tv/ch/9710.png")
    )

    fun getTvShows(groupName: String? = null): List<TvShow> {
        val filtered = if (groupName != null) channels.filter { it.group == groupName } else channels
        val packageName = com.nexastream.app.NexastreamApp.instance.packageName
        return filtered.map { data ->
            val posterUrl = when {
                data.id == "sky-premier-liga" || data.name.contains("Premier Liga", ignoreCase = true) -> {
                    "android.resource://$packageName/drawable/skysport2"
                }
                data.group == "Sky Sports" || data.name.contains("Sky Sport", ignoreCase = true) -> {
                    "android.resource://$packageName/drawable/skysport"
                }
                else -> {
                    data.logo ?: "https://i.ibb.co/W1d0CxF/Logo-IPTV-All-World.jpg"
                }
            }
            TvShow(
                id = data.id,
                title = data.name,
                poster = posterUrl,
                quality = "LIVE",
                providerName = NexaHomeProvider.name
            ).apply { 
                itemType = com.nexastream.app.adapters.AppAdapter.Type.TV_SHOW_MOBILE_ITEM 
            }
        }
    }
}
