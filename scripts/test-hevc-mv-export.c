#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/motion_vector.h>

struct TestStats {
    int64_t frames;
    int64_t frames_with_vectors;
    int64_t vectors;
    int64_t past_vectors;
    int64_t future_vectors;
};

static void print_error(const char *what, int error)
{
    char text[AV_ERROR_MAX_STRING_SIZE];
    av_strerror(error, text, sizeof(text));
    fprintf(stderr, "%s: %s\n", what, text);
}

static int check_frame(const AVFrame *frame, struct TestStats *stats)
{
    const AVFrameSideData *side_data = av_frame_get_side_data(
        frame, AV_FRAME_DATA_MOTION_VECTORS);
    const AVMotionVector *vectors;
    size_t count;
    size_t i;

    stats->frames++;
    if (!side_data)
        return 0;
    if (side_data->size % sizeof(*vectors)) {
        fprintf(stderr, "malformed HEVC motion-vector side-data size: %zu\n",
                side_data->size);
        return -1;
    }

    vectors = (const AVMotionVector *)side_data->data;
    count = side_data->size / sizeof(*vectors);
    if (!count)
        return 0;
    stats->frames_with_vectors++;

    for (i = 0; i < count; i++) {
        const AVMotionVector *mv = &vectors[i];
        if ((mv->source != -1 && mv->source != 1) ||
            mv->motion_scale != 4 || !mv->w || !mv->h) {
            fprintf(stderr,
                    "invalid HEVC AVMotionVector: source=%d scale=%d size=%ux%u\n",
                    mv->source, mv->motion_scale, mv->w, mv->h);
            return -1;
        }
        stats->vectors++;
        if (mv->source < 0)
            stats->past_vectors++;
        else
            stats->future_vectors++;
    }
    return 0;
}

static int drain_decoder(AVCodecContext *decoder, AVFrame *frame,
                         struct TestStats *stats)
{
    int ret;
    while ((ret = avcodec_receive_frame(decoder, frame)) >= 0) {
        if (check_frame(frame, stats) < 0) {
            av_frame_unref(frame);
            return AVERROR_INVALIDDATA;
        }
        av_frame_unref(frame);
    }
    if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF)
        return 0;
    return ret;
}

int main(int argc, char **argv)
{
    AVFormatContext *format = NULL;
    AVCodecContext *decoder = NULL;
    AVPacket *packet = NULL;
    AVFrame *frame = NULL;
    const AVInputFormat *hevc_demuxer;
    const AVCodec *codec;
    struct TestStats stats = { 0 };
    int stream_index;
    int ret;

    if (argc != 2) {
        fprintf(stderr, "usage: %s <known-good-hevc-b-frame-clip>\n", argv[0]);
        return 2;
    }

    hevc_demuxer = av_find_input_format("hevc");
    if (!hevc_demuxer) {
        fprintf(stderr, "FFmpeg HEVC demuxer was not built\n");
        return 1;
    }
    ret = avformat_open_input(&format, argv[1], hevc_demuxer, NULL);
    if (ret < 0) {
        print_error("open HEVC fixture", ret);
        goto fail;
    }
    ret = avformat_find_stream_info(format, NULL);
    if (ret < 0) {
        print_error("read HEVC fixture stream info", ret);
        goto fail;
    }
    stream_index = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1,
                                       &codec, 0);
    if (stream_index < 0 || !codec || codec->id != AV_CODEC_ID_HEVC) {
        fprintf(stderr, "fixture did not resolve to the HEVC software decoder\n");
        goto fail;
    }

    decoder = avcodec_alloc_context3(codec);
    if (!decoder) {
        fprintf(stderr, "could not allocate HEVC decoder context\n");
        goto fail;
    }
    ret = avcodec_parameters_to_context(decoder,
                                        format->streams[stream_index]->codecpar);
    if (ret < 0) {
        print_error("copy HEVC stream parameters", ret);
        goto fail;
    }
    /* This is the same FFmpeg legacy flag used by MPV's vd-lavc-o override. */
    decoder->flags2 |= AV_CODEC_FLAG2_EXPORT_MVS;
    decoder->thread_count = 1;
    decoder->thread_type = 0;
    ret = avcodec_open2(decoder, codec, NULL);
    if (ret < 0) {
        print_error("open patched HEVC decoder", ret);
        goto fail;
    }

    packet = av_packet_alloc();
    frame = av_frame_alloc();
    if (!packet || !frame) {
        fprintf(stderr, "could not allocate HEVC test packet/frame\n");
        goto fail;
    }

    while ((ret = av_read_frame(format, packet)) >= 0) {
        if (packet->stream_index == stream_index) {
            ret = avcodec_send_packet(decoder, packet);
            if (ret == AVERROR(EAGAIN)) {
                ret = drain_decoder(decoder, frame, &stats);
                if (ret >= 0)
                    ret = avcodec_send_packet(decoder, packet);
            }
            if (ret < 0) {
                print_error("send HEVC packet", ret);
                av_packet_unref(packet);
                goto fail;
            }
            ret = drain_decoder(decoder, frame, &stats);
            if (ret < 0) {
                print_error("decode HEVC packet", ret);
                av_packet_unref(packet);
                goto fail;
            }
        }
        av_packet_unref(packet);
    }
    if (ret != AVERROR_EOF) {
        print_error("read HEVC packet", ret);
        goto fail;
    }

    ret = avcodec_send_packet(decoder, NULL);
    if (ret < 0 && ret != AVERROR_EOF) {
        print_error("flush HEVC decoder", ret);
        goto fail;
    }
    ret = drain_decoder(decoder, frame, &stats);
    if (ret < 0) {
        print_error("drain HEVC decoder", ret);
        goto fail;
    }

    printf("HEVC export test: decoded_frames=%" PRId64
           " frames_with_vectors=%" PRId64 " vectors=%" PRId64
           " past=%" PRId64 " future=%" PRId64 "\n",
           stats.frames, stats.frames_with_vectors, stats.vectors,
           stats.past_vectors, stats.future_vectors);
    if (stats.frames < 48 || !stats.frames_with_vectors || !stats.vectors ||
        !stats.past_vectors || !stats.future_vectors) {
        fprintf(stderr, "HEVC B-frame motion-vector export did not meet expectations\n");
        goto fail;
    }

    av_frame_free(&frame);
    av_packet_free(&packet);
    avcodec_free_context(&decoder);
    avformat_close_input(&format);
    return 0;

fail:
    av_frame_free(&frame);
    av_packet_free(&packet);
    avcodec_free_context(&decoder);
    avformat_close_input(&format);
    return 1;
}
