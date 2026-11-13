/*
 * libvpx stub for the Android port of GZDoom.
 * Every decoder entry point fails cleanly, so VP8/VP9 cutscenes are simply
 * reported as "cannot be played" by the engine. Everything else is unaffected.
 */
#include <stddef.h>
#include "vpx/vpx_decoder.h"

vpx_codec_iface_t vpx_codec_vp8_dx_algo = { "vp8-stub" };
vpx_codec_iface_t vpx_codec_vp9_dx_algo = { "vp9-stub" };

vpx_codec_err_t vpx_codec_dec_init(vpx_codec_ctx_t *ctx, vpx_codec_iface_t *iface,
                                   const vpx_codec_dec_cfg_t *cfg, long flags)
{
	(void)ctx; (void)iface; (void)cfg; (void)flags;
	return VPX_CODEC_UNSUP_FEATURE;
}

vpx_codec_err_t vpx_codec_decode(vpx_codec_ctx_t *ctx, const unsigned char *data,
                                 unsigned int data_sz, void *user_priv, long deadline)
{
	(void)ctx; (void)data; (void)data_sz; (void)user_priv; (void)deadline;
	return VPX_CODEC_ERROR;
}

vpx_image_t *vpx_codec_get_frame(vpx_codec_ctx_t *ctx, vpx_codec_iter_t *iter)
{
	(void)ctx; (void)iter;
	return NULL;
}

vpx_codec_err_t vpx_codec_control(vpx_codec_ctx_t *ctx, int ctrl_id, ...)
{
	(void)ctx; (void)ctrl_id;
	return VPX_CODEC_ERROR;
}

vpx_codec_err_t vpx_codec_destroy(vpx_codec_ctx_t *ctx)
{
	(void)ctx;
	return VPX_CODEC_OK;
}
