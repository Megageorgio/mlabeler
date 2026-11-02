package mlabeler.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

internal class AndroidPlaybackTrack(rate: Int) : PlaybackTrack {
    private val track: AudioTrack
    init {
        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "Unsupported audio sample rate: $rate" }
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minimum, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("Could not initialize audio output")
        }
    }
    override val bufferFrames get() = track.bufferCapacityInFrames
    override fun play() = track.play()
    override fun write(data: ShortArray, offset: Int, count: Int) = track.write(data, offset, count, AudioTrack.WRITE_NON_BLOCKING)
    override fun head() = track.playbackHeadPosition
    override fun pause() = track.pause()
    override fun flush() = track.flush()
    override fun stop() = track.stop()
    override fun release() = track.release()
}
