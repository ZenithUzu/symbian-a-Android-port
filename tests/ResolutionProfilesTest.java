import j2a.core.ResolutionProfiles;

public class ResolutionProfilesTest {
    static void check(int w, int h, int turn, double ratio, int expectedW, int expectedH) {
        int[] r = ResolutionProfiles.panorama(w, h, turn, ratio);
        if (r[0] != expectedW || r[1] != expectedH) throw new AssertionError(r[0] + "x" + r[1]);
    }
    public static void main(String[] args) {
        check(320,240,0,16.0/9,427,240);
        check(240,320,0,16.0/9,569,320);
        check(240,320,90,16.0/9,240,427);
        check(240,320,270,20.0/9,240,534);
        check(800,352,0,16.0/9,800,352); // never shrink a game already wider than target
        check(320,240,0,20.0/9,534,240); // always calculated from native size
        try { ResolutionProfiles.panorama(2048,2048,0,21.0/9); throw new AssertionError("Missing bounds check"); }
        catch (IllegalArgumentException expected) {}
        try { ResolutionProfiles.panorama(320,240,0,Double.NaN); throw new AssertionError("NaN accepted"); }
        catch (IllegalArgumentException expected) {}
        System.out.println("Resolution profiles OK");
    }
}
