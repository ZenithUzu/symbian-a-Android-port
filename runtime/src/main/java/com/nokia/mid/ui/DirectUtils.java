package com.nokia.mid.ui;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

public class DirectUtils {
    public static DirectGraphics getDirectGraphics(Graphics g) {
        return new DirectGraphicsImpl(g);
    }

    public static Image createImage(byte[] imageData, int imageOffset, int imageLength) {
        return Image.createImage(imageData, imageOffset, imageLength);
    }

    public static Image createImage(int width, int height, int argb) {
        return Image.j2aCreateMutable(width, height, argb);
    }
}
