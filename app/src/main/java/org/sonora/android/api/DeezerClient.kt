package org.sonora.android.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLEncoder

/**
 * Robust Deezer API client providing search, charts, album, artist, and track metadata
 * with high-resolution artwork extraction.
 */
open class DeezerClient(
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deezer.com"
        private const val PROVIDER_NAME = "Deezer"
    }

    private val gson = HttpClientProvider.gson

    /**
     * Search tracks matching a text query.
     */
    open suspend fun searchTracks(query: String, limit: Int = 25): Result<List<Track>> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "$baseUrl/search?q=$encodedQuery&limit=$limit"
            val responseResult = HttpClientProvider.getJson(url)

            responseResult.fold(
                onSuccess = { json ->
                    val searchResponse = gson.fromJson(json, DeezerSearchResponse::class.java)
                    if (searchResponse?.error != null) {
                        return@withContext Result.failure(IOException("Deezer API error: ${searchResponse.error.message}"))
                    }
                    val tracks = searchResponse?.data.orEmpty()
                        .filter { (it.duration ?: 0L) > 0L && !it.title.isNullOrBlank() }
                        .map { it.toDomainTrack() }
                    Result.success(tracks)
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get top tracks from Deezer global chart.
     */
    suspend fun getChartTopTracks(limit: Int = 50): Result<List<Track>> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/chart/0/tracks?limit=$limit"
            val responseResult = HttpClientProvider.getJson(url)

            responseResult.fold(
                onSuccess = { json ->
                    val chartResponse = gson.fromJson(json, DeezerSearchResponse::class.java)
                    if (chartResponse?.error != null) {
                        return@withContext Result.failure(IOException("Deezer chart error: ${chartResponse.error.message}"))
                    }
                    val tracks = chartResponse?.data.orEmpty()
                        .filter { (it.duration ?: 0L) > 0L && !it.title.isNullOrBlank() }
                        .map { it.toDomainTrack() }
                    Result.success(tracks)
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get detailed information for an album.
     */
    suspend fun getAlbum(albumId: String): Result<Album> = withContext(Dispatchers.IO) {
        try {
            val cleanId = albumId.removePrefix("dz_").trim()
            val url = "$baseUrl/album/$cleanId"
            val responseResult = HttpClientProvider.getJson(url)

            responseResult.fold(
                onSuccess = { json ->
                    val albumDto = gson.fromJson(json, DeezerAlbumDto::class.java)
                    if (albumDto?.error != null) {
                        return@withContext Result.failure(IOException("Deezer album error: ${albumDto.error.message}"))
                    }
                    if (albumDto == null || albumDto.id == 0L) {
                        return@withContext Result.failure(IOException("Album not found: $albumId"))
                    }
                    Result.success(albumDto.toDomainAlbum())
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get tracks belonging to an album.
     */
    suspend fun getAlbumTracks(albumId: String): Result<List<Track>> = withContext(Dispatchers.IO) {
        try {
            val cleanId = albumId.removePrefix("dz_").trim()
            val url = "$baseUrl/album/$cleanId/tracks?limit=100"
            val responseResult = HttpClientProvider.getJson(url)

            responseResult.fold(
                onSuccess = { json ->
                    val trackListResponse = gson.fromJson(json, DeezerTrackListResponse::class.java)
                    if (trackListResponse?.error != null) {
                        return@withContext Result.failure(IOException("Deezer tracks error: ${trackListResponse.error.message}"))
                    }
                    // If track objects in album/tracks lack album cover, retrieve album details or fallback
                    val albumDetail = getAlbum(cleanId).getOrNull()
                    val tracks = trackListResponse?.data.orEmpty()
                        .filter { (it.duration ?: 0L) > 0L && !it.title.isNullOrBlank() }
                        .map { trackDto ->
                            trackDto.toDomainTrack(fallbackAlbum = albumDetail)
                        }
                    Result.success(tracks)
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get detailed album metadata along with its complete tracklist.
     */
    suspend fun getAlbumDetail(albumId: String): Result<Pair<Album, List<Track>>> = withContext(Dispatchers.IO) {
        try {
            val cleanId = albumId.removePrefix("dz_").trim()
            val url = "$baseUrl/album/$cleanId"
            val responseResult = HttpClientProvider.getJson(url)

            responseResult.fold(
                onSuccess = { json ->
                    val albumDto = gson.fromJson(json, DeezerAlbumDto::class.java)
                    if (albumDto?.error != null) {
                        return@withContext Result.failure(IOException("Deezer album error: ${albumDto.error.message}"))
                    }
                    if (albumDto == null || albumDto.id == 0L) {
                        return@withContext Result.failure(IOException("Album not found: $albumId"))
                    }
                    val album = albumDto.toDomainAlbum()
                    val embeddedTracks = albumDto.tracks?.data.orEmpty()
                    val tracks = if (embeddedTracks.isNotEmpty()) {
                        embeddedTracks.map { it.toDomainTrack(fallbackAlbum = album) }
                    } else {
                        getAlbumTracks(cleanId).getOrElse { emptyList() }
                    }
                    Result.success(Pair(album, tracks))
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get detailed artist profile.
     */
    suspend fun getArtist(artistId: String): Result<Artist> = withContext(Dispatchers.IO) {
        try {
            val cleanId = artistId.removePrefix("dz_").trim()
            val url = "$baseUrl/artist/$cleanId"
            val responseResult = HttpClientProvider.getJson(url)

            responseResult.fold(
                onSuccess = { json ->
                    val artistDto = gson.fromJson(json, DeezerArtistDto::class.java)
                    if (artistDto?.error != null) {
                        return@withContext Result.failure(IOException("Deezer artist error: ${artistDto.error.message}"))
                    }
                    if (artistDto == null || artistDto.id == 0L) {
                        return@withContext Result.failure(IOException("Artist not found: $artistId"))
                    }
                    Result.success(artistDto.toDomainArtist())
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get detailed artist profile (explicit alias for getArtist).
     */
    suspend fun getArtistDetail(artistId: String): Result<Artist> = getArtist(artistId)

    /**
     * Get top tracks for an artist.
     */
    open suspend fun getArtistTopTracks(artistId: String, limit: Int = 20): Result<List<Track>> = withContext(Dispatchers.IO) {
        try {
            val cleanId = artistId.removePrefix("dz_").trim()
            val url = "$baseUrl/artist/$cleanId/top?limit=$limit"
            val responseResult = HttpClientProvider.getJson(url)

            responseResult.fold(
                onSuccess = { json ->
                    val tracksResponse = gson.fromJson(json, DeezerSearchResponse::class.java)
                    if (tracksResponse?.error != null) {
                        return@withContext Result.failure(IOException("Deezer artist top error: ${tracksResponse.error.message}"))
                    }
                    val tracks = tracksResponse?.data.orEmpty()
                        .filter { (it.duration ?: 0L) > 0L && !it.title.isNullOrBlank() }
                        .map { it.toDomainTrack() }
                    Result.success(tracks)
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get continuous radio track stream based on an artist and similar styles.
     */
    open suspend fun getArtistRadio(artistId: String, limit: Int = 25): Result<List<Track>> = withContext(Dispatchers.IO) {
        try {
            val cleanId = artistId.removePrefix("dz_").trim()
            val url = "$baseUrl/artist/$cleanId/radio?limit=$limit"
            val responseResult = HttpClientProvider.getJson(url)

            responseResult.fold(
                onSuccess = { json ->
                    val tracksResponse = gson.fromJson(json, DeezerSearchResponse::class.java)
                    if (tracksResponse?.error != null) {
                        return@withContext Result.failure(IOException("Deezer artist radio error: ${tracksResponse.error.message}"))
                    }
                    val tracks = tracksResponse?.data.orEmpty()
                        .filter { (it.duration ?: 0L) > 0L && !it.title.isNullOrBlank() }
                        .map { it.toDomainTrack() }
                    Result.success(tracks)
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get related artists based on musical genre and style similarity.
     */
    open suspend fun getRelatedArtists(artistId: String, limit: Int = 10): Result<List<Artist>> = withContext(Dispatchers.IO) {
        try {
            val cleanId = artistId.removePrefix("dz_").trim()
            val url = "$baseUrl/artist/$cleanId/related?limit=$limit"
            val responseResult = HttpClientProvider.getJson(url)

            responseResult.fold(
                onSuccess = { json ->
                    val response = gson.fromJson(json, DeezerArtistSearchResponse::class.java)
                    if (response?.error != null) {
                        return@withContext Result.failure(IOException("Deezer related artists error: ${response.error.message}"))
                    }
                    val artists = response?.data.orEmpty().map { it.toDomainArtist() }
                    Result.success(artists)
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get discography / albums for an artist.
     */
    suspend fun getArtistAlbums(artistId: String, limit: Int = 50): Result<List<Album>> = withContext(Dispatchers.IO) {
        try {
            val cleanId = artistId.removePrefix("dz_").trim()
            val url = "$baseUrl/artist/$cleanId/albums?limit=$limit"
            val responseResult = HttpClientProvider.getJson(url)

            responseResult.fold(
                onSuccess = { json ->
                    val response = gson.fromJson(json, DeezerAlbumSearchResponse::class.java)
                    if (response?.error != null) {
                        return@withContext Result.failure(IOException("Deezer artist albums error: ${response.error.message}"))
                    }
                    val albums = response?.data.orEmpty().map { it.toDomainAlbum() }
                    Result.success(albums)
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get single track details with high-res cover art.
     */
    open suspend fun getTrack(trackId: String): Result<Track> = withContext(Dispatchers.IO) {
        try {
            val cleanId = trackId.removePrefix("dz_").trim()
            val url = "$baseUrl/track/$cleanId"
            val responseResult = HttpClientProvider.getJson(url)

            responseResult.fold(
                onSuccess = { json ->
                    val trackDto = gson.fromJson(json, DeezerTrackDto::class.java)
                    if (trackDto?.error != null) {
                        return@withContext Result.failure(IOException("Deezer track error: ${trackDto.error.message}"))
                    }
                    if (trackDto == null || trackDto.id == 0L) {
                        return@withContext Result.failure(IOException("Track not found: $trackId"))
                    }
                    Result.success(trackDto.toDomainTrack())
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Search albums matching query.
     */
    suspend fun searchAlbums(query: String, limit: Int = 25): Result<List<Album>> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "$baseUrl/search/album?q=$encodedQuery&limit=$limit"
            val responseResult = HttpClientProvider.getJson(url)

            responseResult.fold(
                onSuccess = { json ->
                    val response = gson.fromJson(json, DeezerAlbumSearchResponse::class.java)
                    val albums = response?.data.orEmpty().map { it.toDomainAlbum() }
                    Result.success(albums)
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Search artists matching query.
     */
    open suspend fun searchArtists(query: String, limit: Int = 25): Result<List<Artist>> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "$baseUrl/search/artist?q=$encodedQuery&limit=$limit"
            val responseResult = HttpClientProvider.getJson(url)

            responseResult.fold(
                onSuccess = { json ->
                    val response = gson.fromJson(json, DeezerArtistSearchResponse::class.java)
                    val artists = response?.data.orEmpty().map { it.toDomainArtist() }
                    Result.success(artists)
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Internal DTOs and Mappings ---

    private fun bestCover(
        coverXl: String?,
        coverBig: String?,
        coverMed: String?,
        coverSm: String?,
        cover: String?
    ): String? {
        return coverXl?.takeIf { it.isNotBlank() }
            ?: coverBig?.takeIf { it.isNotBlank() }
            ?: coverMed?.takeIf { it.isNotBlank() }
            ?: cover?.takeIf { it.isNotBlank() }
            ?: coverSm?.takeIf { it.isNotBlank() }
    }

    private fun bestPicture(
        picXl: String?,
        picBig: String?,
        picMed: String?,
        picSm: String?,
        pic: String?
    ): String? {
        return picXl?.takeIf { it.isNotBlank() }
            ?: picBig?.takeIf { it.isNotBlank() }
            ?: picMed?.takeIf { it.isNotBlank() }
            ?: pic?.takeIf { it.isNotBlank() }
            ?: picSm?.takeIf { it.isNotBlank() }
    }

    private fun DeezerTrackDto.toDomainTrack(fallbackAlbum: Album? = null): Track {
        val resolvedCover = bestCover(
            album?.coverXl,
            album?.coverBig,
            album?.coverMedium,
            album?.coverSmall,
            album?.cover
        ) ?: fallbackAlbum?.coverUrl

        return Track(
            id = id.toString(),
            title = title.orEmpty(),
            artist = artist?.name ?: fallbackAlbum?.artist ?: "Unknown Artist",
            album = album?.title ?: fallbackAlbum?.title,
            durationMs = (duration ?: 0L) * 1000L,
            coverUrl = resolvedCover,
            streamUrl = preview,
            provider = PROVIDER_NAME,
            artistId = artist?.id?.takeIf { it > 0 }?.toString(),
            bpm = bpm?.takeIf { it > 0f },
            gain = gain,
            rank = rank
        )
    }

    private fun DeezerAlbumDto.toDomainAlbum(): Album {
        return Album(
            id = id.toString(),
            title = title.orEmpty(),
            artist = artist?.name ?: "Unknown Artist",
            coverUrl = bestCover(coverXl, coverBig, coverMedium, coverSmall, cover),
            trackCount = nbTracks ?: 0,
            releaseDate = releaseDate,
            label = label
        )
    }

    private fun DeezerArtistDto.toDomainArtist(): Artist {
        return Artist(
            id = id.toString(),
            name = name.orEmpty(),
            pictureUrl = bestPicture(pictureXl, pictureBig, pictureMedium, pictureSmall, picture),
            nbAlbum = nbAlbum ?: 0,
            nbFan = nbFan ?: 0,
            radio = radio ?: false
        )
    }

    private data class DeezerApiError(
        val type: String?,
        val message: String?,
        val code: Int?
    )

    private data class DeezerSearchResponse(
        val data: List<DeezerTrackDto>?,
        val total: Int?,
        val next: String?,
        val error: DeezerApiError?
    )

    private data class DeezerTrackListResponse(
        val data: List<DeezerTrackDto>?,
        val total: Int?,
        val error: DeezerApiError?
    )

    private data class DeezerAlbumSearchResponse(
        val data: List<DeezerAlbumDto>?,
        val total: Int?,
        val error: DeezerApiError?
    )

    private data class DeezerArtistSearchResponse(
        val data: List<DeezerArtistDto>?,
        val total: Int?,
        val error: DeezerApiError?
    )

    private data class DeezerTrackDto(
        val id: Long = 0,
        val title: String? = null,
        val duration: Long? = null,
        val preview: String? = null,
        val artist: DeezerArtistRefDto? = null,
        val album: DeezerAlbumRefDto? = null,
        val bpm: Float? = null,
        val gain: Float? = null,
        val rank: Int? = null,
        val error: DeezerApiError? = null
    )

    private data class DeezerArtistRefDto(
        val id: Long = 0,
        val name: String? = null
    )

    private data class DeezerAlbumRefDto(
        val id: Long = 0,
        val title: String? = null,
        val cover: String? = null,
        @SerializedName("cover_small") val coverSmall: String? = null,
        @SerializedName("cover_medium") val coverMedium: String? = null,
        @SerializedName("cover_big") val coverBig: String? = null,
        @SerializedName("cover_xl") val coverXl: String? = null
    )

    private data class DeezerAlbumDto(
        val id: Long = 0,
        val title: String? = null,
        val cover: String? = null,
        @SerializedName("cover_small") val coverSmall: String? = null,
        @SerializedName("cover_medium") val coverMedium: String? = null,
        @SerializedName("cover_big") val coverBig: String? = null,
        @SerializedName("cover_xl") val coverXl: String? = null,
        @SerializedName("nb_tracks") val nbTracks: Int? = null,
        @SerializedName("release_date") val releaseDate: String? = null,
        val label: String? = null,
        val artist: DeezerArtistRefDto? = null,
        val tracks: DeezerTrackListResponse? = null,
        val error: DeezerApiError? = null
    )

    private data class DeezerArtistDto(
        val id: Long = 0,
        val name: String? = null,
        val picture: String? = null,
        @SerializedName("picture_small") val pictureSmall: String? = null,
        @SerializedName("picture_medium") val pictureMedium: String? = null,
        @SerializedName("picture_big") val pictureBig: String? = null,
        @SerializedName("picture_xl") val pictureXl: String? = null,
        @SerializedName("nb_album") val nbAlbum: Int? = null,
        @SerializedName("nb_fan") val nbFan: Int? = null,
        val radio: Boolean? = null,
        val error: DeezerApiError? = null
    )
}
