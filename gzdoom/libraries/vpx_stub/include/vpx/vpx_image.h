/*
 * Minimal libvpx "image" stub header used by the Android port.
 * Only the parts of the libvpx API which GZDoom touches are declared.
 * Video (VP8/VP9 .ivf) playback is NOT available in this build.
 */
#ifndef VPX_STUB_VPX_IMAGE_H
#define VPX_STUB_VPX_IMAGE_H

#ifdef __cplusplus
extern "C" {
#endif

typedef enum vpx_img_fmt {
	VPX_IMG_FMT_NONE = 0,
	VPX_IMG_FMT_I420 = 0x102,
	VPX_IMG_FMT_I422 = 0x105,
	VPX_IMG_FMT_I444 = 0x106,
	VPX_IMG_FMT_I440 = 0x107
} vpx_img_fmt_t;

#define VPX_PLANE_PACKED 0
#define VPX_PLANE_Y 0
#define VPX_PLANE_U 1
#define VPX_PLANE_V 2
#define VPX_PLANE_ALPHA 3

typedef struct vpx_image {
	vpx_img_fmt_t fmt;
	unsigned int w;
	unsigned int h;
	unsigned int d_w;
	unsigned int d_h;
	unsigned int x_chroma_shift;
	unsigned int y_chroma_shift;
	unsigned char *planes[4];
	int stride[4];
	int bps;
} vpx_image_t;

#ifdef __cplusplus
}
#endif

#endif
