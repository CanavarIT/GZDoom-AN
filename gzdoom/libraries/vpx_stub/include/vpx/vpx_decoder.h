#ifndef VPX_STUB_VPX_DECODER_H
#define VPX_STUB_VPX_DECODER_H

#include "vpx_image.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum vpx_codec_err {
	VPX_CODEC_OK = 0,
	VPX_CODEC_ERROR = 1,
	VPX_CODEC_UNSUP_FEATURE = 3
} vpx_codec_err_t;

typedef const void *vpx_codec_iter_t;

typedef struct vpx_codec_iface {
	const char *name;
} vpx_codec_iface_t;

typedef struct vpx_codec_ctx {
	const vpx_codec_iface_t *iface;
	void *priv;
} vpx_codec_ctx_t;

typedef struct vpx_codec_dec_cfg {
	unsigned int threads;
	unsigned int w;
	unsigned int h;
} vpx_codec_dec_cfg_t;

extern vpx_codec_iface_t vpx_codec_vp8_dx_algo;
extern vpx_codec_iface_t vpx_codec_vp9_dx_algo;

vpx_codec_err_t vpx_codec_dec_init(vpx_codec_ctx_t *ctx, vpx_codec_iface_t *iface,
                                   const vpx_codec_dec_cfg_t *cfg, long flags);
vpx_codec_err_t vpx_codec_decode(vpx_codec_ctx_t *ctx, const unsigned char *data,
                                 unsigned int data_sz, void *user_priv, long deadline);
vpx_image_t *vpx_codec_get_frame(vpx_codec_ctx_t *ctx, vpx_codec_iter_t *iter);
vpx_codec_err_t vpx_codec_control(vpx_codec_ctx_t *ctx, int ctrl_id, ...);
vpx_codec_err_t vpx_codec_destroy(vpx_codec_ctx_t *ctx);

#ifdef __cplusplus
}
#endif

#endif
