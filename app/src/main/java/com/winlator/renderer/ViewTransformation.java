package com.winlator.renderer;

public class ViewTransformation {
    /**
     * wow-mobile: rows at the bottom of the X screen that are never shown. The game is
     * rendered that much taller and the extra rows fall below the display; the WoWMobileLink
     * addon draws its data row there. Set before the renderer and touchpad size themselves.
     */
    public static int cropBottom = 0;

    public int viewOffsetX;
    /** GL viewport y (from the bottom of the surface). */
    public int viewOffsetY;
    /** Distance from the top of the view to the top of the X screen, for touch mapping. */
    public int viewOffsetTop;
    public int viewWidth;
    public int viewHeight;
    public float aspect;
    public float sceneScaleX;
    public float sceneScaleY;
    public float sceneOffsetX;
    public float sceneOffsetY;

    public void update(int outerWidth, int outerHeight, int innerWidth, int innerHeight) {
        int crop = cropBottom > 0 && cropBottom < innerHeight ? cropBottom : 0;
        int visibleHeight = innerHeight - crop;
        aspect = Math.min((float)outerWidth / innerWidth, (float)outerHeight / visibleHeight);
        viewWidth = (int)Math.ceil(innerWidth * aspect);
        viewHeight = (int)Math.ceil(innerHeight * aspect);
        viewOffsetX = (int)((outerWidth - innerWidth * aspect) * 0.5f);
        viewOffsetTop = (int)((outerHeight - visibleHeight * aspect) * 0.5f);
        // The cropped rows hang below the surface (negative viewport y is clipped by GL).
        viewOffsetY = (int)((outerHeight - visibleHeight * aspect) * 0.5f - crop * aspect);

        sceneScaleX = (innerWidth * aspect) / outerWidth;
        sceneScaleY = (innerHeight * aspect) / outerHeight;
        sceneOffsetX = (innerWidth - innerWidth * sceneScaleX) * 0.5f;
        sceneOffsetY = (innerHeight - innerHeight * sceneScaleY) * 0.5f;
    }
}
