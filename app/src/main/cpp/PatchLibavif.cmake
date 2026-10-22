# Keeps the inner layers of a layered (progressive) image at their own size:
# libavif scales every decoded layer up to the full image size, which makes a
# small preview layer cost as much to convert and scale down as the full image.
# Only for a single color tile without alpha, and never for the last layer.
# Run from libavif's source directory by FetchContent's patch step.

set(file "src/read.c")
file(READ "${file}" text)
set(marker "Keep inner layers at their own size")
string(FIND "${text}" "${marker}" done)
if(NOT done EQUAL -1)
    return()
endif()

set(old "        if ((tile->width != tile->image->width) || (tile->height != tile->image->height)) {\n            if (avifImageScaleWithLimit(")
set(new "        // ${marker}.\n        const avifBool innerLayer = decoder->progressiveState == AVIF_PROGRESSIVE_STATE_ACTIVE && !decoder->alphaPresent &&\n                                    info->tileCount == 1 && tile->input->itemCategory == AVIF_ITEM_COLOR &&\n                                    nextImageIndex + 1 < (uint32_t)decoder->imageCount;\n        if (!innerLayer && ((tile->width != tile->image->width) || (tile->height != tile->image->height))) {\n            if (avifImageScaleWithLimit(")
string(FIND "${text}" "${old}" at)
if(at EQUAL -1)
    message(FATAL_ERROR "libavif patch: the layer scaling code was not found in ${file}")
endif()
string(REPLACE "${old}" "${new}" text "${text}")
file(WRITE "${file}" "${text}")
