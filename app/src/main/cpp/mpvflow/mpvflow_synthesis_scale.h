#ifndef MPVFLOW_SYNTHESIS_SCALE_H
#define MPVFLOW_SYNTHESIS_SCALE_H

#include <stdint.h>

struct mpvflow_synthesis_size {
    int width;
    int height;
};

static inline int mpvflow_scaled_dimension(int dimension, int source_max,
                                           int max_dimension)
{
    if (dimension <= 0 || source_max <= 0 || max_dimension <= 0 ||
        source_max <= max_dimension)
        return dimension;

    const int64_t numerator = (int64_t) dimension * max_dimension + source_max / 2;
    const int64_t scaled = numerator / source_max;
    if (scaled < 1)
        return 1;
    if (scaled > dimension)
        return dimension;
    return (int) scaled;
}

static inline struct mpvflow_synthesis_size
mpvflow_synthesis_size_for(int width, int height, int max_dimension)
{
    struct mpvflow_synthesis_size size = { width, height };
    if (width <= 0 || height <= 0 || max_dimension <= 0)
        return size;

    const int source_max = width > height ? width : height;
    if (source_max <= max_dimension)
        return size;

    size.width = mpvflow_scaled_dimension(width, source_max, max_dimension);
    size.height = mpvflow_scaled_dimension(height, source_max, max_dimension);
    /* Preserve MPVFlow's minimum block-sized input on extreme aspect ratios. */
    if (size.width < 8 || size.height < 8) {
        size.width = width;
        size.height = height;
    }
    return size;
}

#endif
