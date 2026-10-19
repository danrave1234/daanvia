package net.drave.atrdiagnostics;
import java.util.*;
public final class GeoMath {
    private GeoMath(){}
    public static double distance(double a,double b,double c,double d){double x=Math.toRadians(c-a),y=Math.toRadians(d-b);double z=Math.sin(x/2)*Math.sin(x/2)+Math.cos(Math.toRadians(a))*Math.cos(Math.toRadians(c))*Math.sin(y/2)*Math.sin(y/2);return 6371000*2*Math.atan2(Math.sqrt(z),Math.sqrt(Math.max(0,1-z)));}
    public static List<double[]> decode(String s){List<double[]> out=new ArrayList<>();int i=0;long lat=0,lon=0;while(i<s.length()){long[] a=number(s,i);i=(int)a[1];long[] b=number(s,i);i=(int)b[1];lat+=a[0];lon+=b[0];if(Math.abs(lat)>90000000||Math.abs(lon)>180000000)throw new IllegalArgumentException("Invalid route geometry");out.add(new double[]{lat/1e6,lon/1e6});}return out;}
    private static long[] number(String s,int i){long n=0;int shift=0,v;do{if(i>=s.length()||shift>55)throw new IllegalArgumentException("Invalid polyline");v=s.charAt(i++)-63;if(v<0||v>63)throw new IllegalArgumentException("Invalid polyline");n|=(long)(v&31)<<shift;shift+=5;}while(v>=32);return new long[]{(n&1)!=0?~(n>>1):n>>1,i};}
    public static double segmentDistance(double lat,double lon,double[] a,double[] b){double k=Math.cos(Math.toRadians(lat));double ax=(a[1]-lon)*k,ay=a[0]-lat,bx=(b[1]-lon)*k,by=b[0]-lat,dx=bx-ax,dy=by-ay;double q=dx*dx+dy*dy;double t=q==0?0:Math.max(0,Math.min(1,-(ax*dx+ay*dy)/q));return Math.hypot(ax+t*dx,ay+t*dy)*111195;}
    /** The closest point to (lat, lon) on segment a-b, as {lat, lon}. */
    public static double[] project(double lat,double lon,double[] a,double[] b){double k=Math.cos(Math.toRadians(lat));double ax=(a[1]-lon)*k,ay=a[0]-lat,bx=(b[1]-lon)*k,by=b[0]-lat,dx=bx-ax,dy=by-ay;double q=dx*dx+dy*dy;double t=q==0?0:Math.max(0,Math.min(1,-(ax*dx+ay*dy)/q));return new double[]{a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t};}
    public static float bearing(double[] a,double[] b){return (float)((Math.toDegrees(Math.atan2((b[1]-a[1])*Math.cos(Math.toRadians(a[0])),b[0]-a[0]))+360)%360);}
    public static double[] safeRect(int width,int height,float left,float top,float right,float bottom){double l=width*Math.max(0,Math.min(.3,left)),t=height*Math.max(0,Math.min(.3,top)),r=width*(1-Math.max(0,Math.min(.3,right))),b=height*(1-Math.max(0,Math.min(.35,bottom)));return new double[]{l,t,r,b};}
}
