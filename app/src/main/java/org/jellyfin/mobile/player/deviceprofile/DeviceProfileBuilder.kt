package org.jellyfin.mobile.player.deviceprofile

import android.media.MediaCodecList
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.sdk.model.api.CodecProfile
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.ContainerProfile
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileCondition
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile
import org.jellyfin.sdk.model.api.TranscodingProfile

class DeviceProfileBuilder(
    private val appPreferences: AppPreferences,
) {
    private val supportedVideoCodecs: Array<Array<String>>
    private val supportedAudioCodecs: Array<Array<String>>
    private val videoCodecsProfiles: Map<String, Set<String>>

    init {
        require(
            SUPPORTED_CONTAINER_FORMATS.size == AVAILABLE_VIDEO_CODECS.size && SUPPORTED_CONTAINER_FORMATS.size == AVAILABLE_AUDIO_CODECS.size,
        )

        // Load Android-supported codecs
        val videoCodecs: MutableMap<String, DeviceCodec.Video> = HashMap()
        val audioCodecs: MutableMap<String, DeviceCodec.Audio> = HashMap()
        val androidCodecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (codecInfo in androidCodecs.codecInfos) {
            if (codecInfo.isEncoder) continue

            for (mimeType in codecInfo.supportedTypes) {
                val codec = DeviceCodec.from(codecInfo.getCapabilitiesForType(mimeType)) ?: continue
                val name = codec.name
                when (codec) {
                    is DeviceCodec.Video -> {
                        if (videoCodecs.containsKey(name)) {
                            videoCodecs[name] = videoCodecs[name]!!.mergeCodec(codec)
                        } else {
                            videoCodecs[name] = codec
                        }
                    }
                    is DeviceCodec.Audio -> {
                        if (audioCodecs.containsKey(mimeType)) {
                            audioCodecs[name] = audioCodecs[name]!!.mergeCodec(codec)
                        } else {
                            audioCodecs[name] = codec
                        }
                    }
                }
            }
        }

        // Build map of supported codecs from device support and hardcoded data
        supportedVideoCodecs = Array(AVAILABLE_VIDEO_CODECS.size) { i ->
            AVAILABLE_VIDEO_CODECS[i].filter { codec ->
                videoCodecs.containsKey(codec)
            }.toTypedArray()
        }
        supportedAudioCodecs = Array(AVAILABLE_AUDIO_CODECS.size) { i ->
            AVAILABLE_AUDIO_CODECS[i].filter { codec ->
                audioCodecs.containsKey(codec) || codec in FORCED_AUDIO_CODECS
            }.toTypedArray()
        }
        videoCodecsProfiles = videoCodecs.entries.associate { (k, v) -> k to v.profiles }
    }

    fun getDeviceProfile(): DeviceProfile {
        val containerProfiles = ArrayList<ContainerProfile>()
        val directPlayProfiles = ArrayList<DirectPlayProfile>()
        val codecProfiles = ArrayList<CodecProfile>()

        for (i in SUPPORTED_CONTAINER_FORMATS.indices) {
            val container = SUPPORTED_CONTAINER_FORMATS[i]
            if (supportedVideoCodecs[i].isNotEmpty()) {
                containerProfiles.add(
                    ContainerProfile(type = DlnaProfileType.VIDEO, container = container, conditions = emptyList()),
                )
                directPlayProfiles.add(
                    DirectPlayProfile(
                        type = DlnaProfileType.VIDEO,
                        container = container,
                        videoCodec = supportedVideoCodecs[i].joinToString(","),
                        audioCodec = supportedAudioCodecs[i].joinToString(","),
                    ),
                )
                for (videoCodec in supportedVideoCodecs[i]) {
                    generateCodecProfile(container, videoCodec)?.let(codecProfiles::add)
                }
            }
            if (supportedAudioCodecs[i].isNotEmpty()) {
                containerProfiles.add(
                    ContainerProfile(type = DlnaProfileType.AUDIO, container = container, conditions = emptyList()),
                )
                directPlayProfiles.add(
                    DirectPlayProfile(
                        type = DlnaProfileType.AUDIO,
                        container = SUPPORTED_CONTAINER_FORMATS[i],
                        audioCodec = supportedAudioCodecs[i].joinToString(","),
                    ),
                )
            }
        }

        val subtitleProfiles = when {
            appPreferences.exoPlayerDirectPlayAss -> {
                getSubtitleProfiles(EXO_EMBEDDED_SUBTITLES + SUBTITLES_SSA, EXO_EXTERNAL_SUBTITLES + SUBTITLES_SSA)
            }
            else -> getSubtitleProfiles(EXO_EMBEDDED_SUBTITLES, EXO_EXTERNAL_SUBTITLES)
        }

        return DeviceProfile(
            name = Constants.APP_INFO_NAME,
            directPlayProfiles = directPlayProfiles,
            transcodingProfiles = buildTranscodingProfiles(),
            containerProfiles = containerProfiles,
            codecProfiles = codecProfiles,
            subtitleProfiles = subtitleProfiles,
            maxStreamingBitrate = MAX_STREAMING_BITRATE,
            maxStaticBitrate = MAX_STATIC_BITRATE,
            musicStreamingTranscodingBitrate = MAX_MUSIC_TRANSCODING_BITRATE,
        )
    }

    /**
     * Orders a list of candidate transcode video codecs based on the user's preferred codec.
     *
     * When the preference is "auto", the candidate list is used as-is, i.e. in its default
     * priority order (see [DEFAULT_VIDEO_CODEC_PRIORITY]: HEVC first). When a specific codec
     * is chosen and is among the candidates, it is floated to the front and the rest keep
     * their default order. Returns a comma-separated string for a [TranscodingProfile]'s
     * videoCodec field.
     *
     * Note on HEVC-first default: on Intel QSV hardware (hevc_qsv vs av1_qsv), HEVC matches
     * or beats AV1 on quality-per-bitrate at useful quality levels, encodes faster, has wider
     * client decode support, and muxes into MPEG-TS cleanly (AV1 forces fMP4). AV1 only edges
     * ahead at very low bitrates. AV1 remains selectable for that case.
     */
    private fun orderVideoCodecs(candidates: List<String>): String {
        val preferred = appPreferences.preferredVideoCodec
        val ordered = if (preferred != Constants.VIDEO_CODEC_AUTO && preferred in candidates) {
            listOf(preferred) + candidates.filter { it != preferred }
        } else {
            candidates
        }
        return ordered.joinToString(",")
    }

    private fun buildTranscodingProfiles(): List<TranscodingProfile> = listOf(
        // fMP4 (HLS) — full codec set; HEVC preferred by default, AV1 selectable
        TranscodingProfile(
            type = DlnaProfileType.VIDEO,
            container = "mp4",
            videoCodec = orderVideoCodecs(DEFAULT_VIDEO_CODEC_PRIORITY),
            audioCodec = "mp1,mp2,mp3,aac,ac3,eac3,dts,mlp,truehd",
            protocol = MediaStreamProtocol.HLS,
            conditions = emptyList(),
        ),
        // MPEG-TS (HLS) — no AV1 (TS can't mux AV1); HEVC preferred over H264
        TranscodingProfile(
            type = DlnaProfileType.VIDEO,
            container = "ts",
            videoCodec = orderVideoCodecs(listOf(Constants.VIDEO_CODEC_HEVC, Constants.VIDEO_CODEC_H264)),
            audioCodec = "mp1,mp2,mp3,aac,ac3,eac3,dts,mlp,truehd",
            protocol = MediaStreamProtocol.HLS,
            conditions = emptyList(),
        ),
        TranscodingProfile(
            type = DlnaProfileType.VIDEO,
            container = "mkv",
            videoCodec = orderVideoCodecs(DEFAULT_VIDEO_CODEC_PRIORITY),
            audioCodec = AVAILABLE_AUDIO_CODECS[SUPPORTED_CONTAINER_FORMATS.indexOf("mkv")].joinToString(","),
            protocol = MediaStreamProtocol.HLS,
            conditions = emptyList(),
        ),
        TranscodingProfile(
            type = DlnaProfileType.AUDIO,
            container = "mp3",
            videoCodec = "",
            audioCodec = "mp3",
            protocol = MediaStreamProtocol.HTTP,
            conditions = emptyList(),
        ),
    )

    private fun generateCodecProfile(
        container: String,
        videoCodec: String,
    ): CodecProfile? {
        val profilesSet = videoCodecsProfiles[videoCodec]
        if (profilesSet?.isNotEmpty() != true) {
            return null
        }

        return CodecProfile(
            type = CodecType.VIDEO,
            container = container,
            codec = videoCodec,
            applyConditions = listOf(),
            conditions = listOf(
                ProfileCondition(
                    condition = ProfileConditionType.EQUALS_ANY,
                    property = ProfileConditionValue.VIDEO_PROFILE,
                    value = profilesSet.joinToString("|"),
                    isRequired = false,
                ),
            ),
        )
    }

    private fun getSubtitleProfiles(embedded: Array<String>, external: Array<String>): List<SubtitleProfile> = ArrayList<SubtitleProfile>().apply {
        for (format in embedded) {
            add(SubtitleProfile(format = format, method = SubtitleDeliveryMethod.EMBED))
        }
        for (format in external) {
            add(SubtitleProfile(format = format, method = SubtitleDeliveryMethod.EXTERNAL))
        }
    }

    fun getExternalPlayerProfile(): DeviceProfile = DeviceProfile(
        name = EXTERNAL_PLAYER_PROFILE_NAME,
        directPlayProfiles = listOf(
            DirectPlayProfile(type = DlnaProfileType.VIDEO, container = ""),
            DirectPlayProfile(type = DlnaProfileType.AUDIO, container = ""),
        ),
        transcodingProfiles = emptyList(),
        containerProfiles = emptyList(),
        codecProfiles = emptyList(),
        subtitleProfiles = buildList {
            EXTERNAL_PLAYER_SUBTITLES.mapTo(this) { format ->
                SubtitleProfile(format = format, method = SubtitleDeliveryMethod.EMBED)
            }
            EXTERNAL_PLAYER_SUBTITLES.mapTo(this) { format ->
                SubtitleProfile(format = format, method = SubtitleDeliveryMethod.EXTERNAL)
            }
        },
        maxStreamingBitrate = Int.MAX_VALUE,
        maxStaticBitrate = Int.MAX_VALUE,
        musicStreamingTranscodingBitrate = Int.MAX_VALUE,
    )

    companion object {
        private const val EXTERNAL_PLAYER_PROFILE_NAME = Constants.APP_INFO_NAME + " External Player"

        /**
         * Default priority order for transcode video codecs when the user preference is "auto".
         * HEVC leads based on measured hevc_qsv vs av1_qsv performance on Intel hardware
         * (see [orderVideoCodecs]). H264 last as the universal fallback.
         */
        private val DEFAULT_VIDEO_CODEC_PRIORITY = listOf(
            Constants.VIDEO_CODEC_HEVC,
            Constants.VIDEO_CODEC_AV1,
            Constants.VIDEO_CODEC_H264,
        )

        /**
         * List of container formats supported by ExoPlayer
         *
         * IMPORTANT: Don't change without updating [AVAILABLE_VIDEO_CODECS] and [AVAILABLE_AUDIO_CODECS]
         */
        private val SUPPORTED_CONTAINER_FORMATS = arrayOf(
            "mp4", "fmp4", "webm", "mkv", "mp3", "ogg", "wav", "mpegts", "flv", "aac", "flac", "3gp",
        )

        /**
         * IMPORTANT: Must have same length as [SUPPORTED_CONTAINER_FORMATS],
         * as it maps the codecs to the containers with the same index!
         */
        private val AVAILABLE_VIDEO_CODECS = arrayOf(
            // mp4
            arrayOf("mpeg1video", "mpeg2video", "h263", "mpeg4", "h264", "hevc", "av1", "vp9"),
            // fmp4
            arrayOf("mpeg1video", "mpeg2video", "h263", "mpeg4", "h264", "hevc", "av1", "vp9"),
            // webm
            arrayOf("vp8", "vp9", "av1"),
            // mkv
            arrayOf("mpeg1video", "mpeg2video", "h263", "mpeg4", "h264", "hevc", "av1", "vp8", "vp9"),
            // mp3
            emptyArray(),
            // ogg
            emptyArray(),
            // wav
            emptyArray(),
            // mpegts
            arrayOf("mpeg1video", "mpeg2video", "mpeg4", "h264", "hevc"),
            // flv
            arrayOf("mpeg4", "h264"),
            // aac
            emptyArray(),
            // flac
            emptyArray(),
            // 3gp
            arrayOf("h263", "mpeg4", "h264", "hevc"),
        )

        /**
         * List of PCM codecs supported by ExoPlayer by default
         */
        private val PCM_CODECS = arrayOf(
            "pcm_s8",
            "pcm_s16be",
            "pcm_s16le",
            "pcm_s24le",
            "pcm_s32le",
            "pcm_f32le",
            "pcm_alaw",
            "pcm_mulaw",
        )

        /**
         * IMPORTANT: Must have same length as [SUPPORTED_CONTAINER_FORMATS],
         * as it maps the codecs to the containers with the same index!
         */
        private val AVAILABLE_AUDIO_CODECS = arrayOf(
            // mp4
            arrayOf("mp1", "mp2", "mp3", "aac", "alac", "ac3", "opus"),
            // fmp4
            arrayOf("mp3", "aac", "ac3", "eac3"),
            // webm
            arrayOf("vorbis", "opus"),
            // mkv
            arrayOf(*PCM_CODECS, "mp1", "mp2", "mp3", "aac", "vorbis", "opus", "flac", "alac", "ac3", "eac3", "dts", "mlp", "truehd"),
            // mp3
            arrayOf("mp3"),
            // ogg
            arrayOf("vorbis", "opus", "flac"),
            // wav
            PCM_CODECS,
            // mpegts
            arrayOf(*PCM_CODECS, "mp1", "mp2", "mp3", "aac", "ac3", "eac3", "dts", "mlp", "truehd"),
            // flv
            arrayOf("mp3", "aac"),
            // aac
            arrayOf("aac"),
            // flac
            arrayOf("flac"),
            // 3gp
            arrayOf("3gpp", "aac", "flac"),
        )

        /**
         * List of audio codecs that will be added to the device profile regardless of [MediaCodecList] advertising them.
         * This is especially useful for codecs supported by decoders integrated to ExoPlayer or added through an extension.
         */
        private val FORCED_AUDIO_CODECS = arrayOf(*PCM_CODECS, "alac", "aac", "ac3", "eac3", "dts", "mlp", "truehd")

        private val EXO_EMBEDDED_SUBTITLES = arrayOf("dvbsub", "pgssub", "srt", "subrip", "ttml")
        private val EXO_EXTERNAL_SUBTITLES = arrayOf("srt", "subrip", "ttml", "vtt", "webvtt")
        private val SUBTITLES_SSA = arrayOf("ssa", "ass")
        private val EXTERNAL_PLAYER_SUBTITLES = arrayOf(
            "ass", "dvbsub", "pgssub", "srt", "srt", "ssa", "subrip", "subrip", "ttml", "ttml", "vtt", "webvtt",
        )

        /**
         * Taken from Jellyfin Web:
         * https://github.com/jellyfin/jellyfin-web/blob/de690740f03c0568ba3061c4c586bd78b375d882/src/scripts/browserDeviceProfile.js#L276
         */
        private const val MAX_STREAMING_BITRATE = 120000000

        /**
         * Taken from Jellyfin Web:
         * https://github.com/jellyfin/jellyfin-web/blob/de690740f03c0568ba3061c4c586bd78b375d882/src/scripts/browserDeviceProfile.js#L372
         */
        private const val MAX_STATIC_BITRATE = 100000000

        /**
         * Taken from Jellyfin Web:
         * https://github.com/jellyfin/jellyfin-web/blob/de690740f03c0568ba3061c4c586bd78b375d882/src/scripts/browserDeviceProfile.js#L373
         */
        private const val MAX_MUSIC_TRANSCODING_BITRATE = 384000
    }
}
