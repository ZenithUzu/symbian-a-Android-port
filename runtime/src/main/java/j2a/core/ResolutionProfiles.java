package j2a.core;

/** Virtual canvas sizes. Widescreen adds horizontal game coordinates, never scales the image. */
public final class ResolutionProfiles {
    private ResolutionProfiles() {}

    public static final int[][] CLASSIC = {
            {96,65}, {101,80}, {128,96}, {96,128}, {120,160}, {160,120},
            {128,128}, {128,160}, {160,128}, {132,176}, {176,132}, {160,160},
            {176,204}, {176,208}, {208,176}, {176,220}, {220,176}, {208,208},
            {240,260}, {240,320}, {320,240}, {240,400}, {400,240}, {240,432}, {432,240},
            {352,416}, {416,352}, {320,480}, {480,320}, {360,640}, {640,360},
            {480,640}, {640,480}, {480,800}, {800,480}, {480,854}, {854,480},
            {640,200}, {640,320}, {800,352}
    };

    /** Keep original visible height, grow width only. Sideways J2ME buffers use swapped axes. */
    public static int[] panorama(int width, int height, int rotation, double aspect) {
        if (width < 64 || height < 64 || width > 2048 || height > 2048 ||
                Double.isNaN(aspect) || Double.isInfinite(aspect) || aspect < 1 || aspect > 4) {
            throw new IllegalArgumentException("Resolución o proporción fuera de rango");
        }
        boolean turned = rotation == 90 || rotation == 270;
        int visibleW = turned ? height : width;
        int visibleH = turned ? width : height;
        int expandedW = Math.max(visibleW, (int)Math.ceil(visibleH * aspect));
        if (expandedW > 2048) throw new IllegalArgumentException("El lienzo panorámico excede 2048 píxeles");
        return turned ? new int[]{visibleH, expandedW} : new int[]{expandedW, visibleH};
    }
}
