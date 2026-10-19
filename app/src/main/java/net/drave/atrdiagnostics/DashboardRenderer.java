package net.drave.atrdiagnostics;
import android.graphics.*;
import android.os.SystemClock;
import org.json.*;
import java.util.*;
public final class DashboardRenderer {
    private final NavSession s;private final Paint p=new Paint(3);private final Path path=new Path();
    private static final int INK=Color.rgb(5,66,56),CREAM=Color.rgb(249,247,240),ROAD=Color.WHITE;
    public DashboardRenderer(NavSession session){s=session;}
    private void text(Canvas c,String str,float x,float y,float size,int color){p.setColor(color);p.setStyle(Paint.Style.FILL);p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));p.setTextSize(size);c.drawText(str,x,y,p);}
    private String fit(String value,float width,float size){p.setTextSize(size);p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));if(p.measureText(value)<=width)return value;while(value.length()>1&&p.measureText(value+"…")>width)value=value.substring(0,value.length()-1);return value+"…";}
    public void draw(Canvas target,int w,int h){
        target.drawColor(Color.BLACK);double[] r=GeoMath.safeRect(w,h,s.insetLeft,s.insetTop,s.insetRight,s.insetBottom);float width=(float)(r[2]-r[0]),height=(float)(r[3]-r[1]);
        target.save();target.clipRect((float)r[0],(float)r[1],(float)r[2],(float)r[3]);target.translate((float)r[0],(float)r[1]);float scale=Math.min(width/800f,height/480f);float vw=width/scale,vh=height/scale;target.scale(scale,scale);target.drawColor(CREAM);
        if(s.calibration){grid(target,vw,vh);target.restore();return;}
        float header=("Large turns".equals(s.layout)?150:104)*s.textScale;
        drawMap(target,vw,vh,header);
        p.setColor(CREAM);p.setStyle(Paint.Style.FILL);target.drawRect(0,0,vw,header,p);
        String distance=s.active?(s.distanceToTurn<1000?Math.max(0,Math.round(s.distanceToTurn/10)*10)+" m":String.format(java.util.Locale.US,"%.1f km",s.distanceToTurn/1000)):"Daanvia";
        boolean stale=s.active&&!s.simulation&&(s.fixAt==0||SystemClock.elapsedRealtime()-s.fixAt>12000||s.accuracy>50);
        if(stale)distance="GPS weak";
        arrow(target,s.instruction,43,50);
        text(target,distance,105,48*s.textScale,40*s.textScale,INK);
        text(target,fit(s.instruction,vw-130,24*s.textScale),105,84*s.textScale,24*s.textScale,INK);
        if(s.simulation)text(target,"SIMULATION",vw-155,21,15,Color.rgb(158,76,10));
        p.setTypeface(Typeface.DEFAULT);p.setTextSize(11);p.setColor(INK);target.drawText("© OpenStreetMap contributors",10,vh-8,p);
        target.restore();
    }
    private void arrow(Canvas c,String instruction,float x,float y){p.setColor(INK);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(10);p.setStrokeJoin(Paint.Join.ROUND);path.reset();String t=instruction.toLowerCase(java.util.Locale.US);if(t.contains("left")){path.moveTo(x+20,y+27);path.lineTo(x+20,y-5);path.lineTo(x-15,y-5);path.moveTo(x,y-20);path.lineTo(x-17,y-5);path.lineTo(x,y+10);}else if(t.contains("right")){path.moveTo(x-12,y+27);path.lineTo(x-12,y-5);path.lineTo(x+23,y-5);path.moveTo(x+8,y-20);path.lineTo(x+25,y-5);path.lineTo(x+8,y+10);}else{path.moveTo(x,y+27);path.lineTo(x,y-23);path.moveTo(x-17,y-7);path.lineTo(x,y-25);path.lineTo(x+17,y-7);}c.drawPath(path,p);p.setStyle(Paint.Style.FILL);}
    private float x(double lon,double center,double meter,float width){return(float)(width/2+(lon-center)*Math.cos(Math.toRadians(s.lat))*111195/meter);}
    private float y(double lat,double center,double meter,float h,float header){return(float)(header+(h-header)*.65-(lat-center)*111195/meter);}
    private void drawMap(Canvas c,float w,float h,float header){
        List<double[]> route=s.shape;
        double meters=(s.active&&s.remaining<1000?2.5:4.5);double lat=s.lat,lon=s.lon;
        p.setColor(Color.rgb(233,231,222));c.drawRect(0,header,w,h,p);
        c.save();c.clipRect(0,header,w,h);
        for(NavSession.Road road:s.roads){if(road.points.length<2||road.maxLat<lat-(h*meters/111195)||road.minLat>lat+(h*meters/111195)||road.maxLon<lon-(w*meters/100000)||road.minLon>lon+(w*meters/100000))continue;path.reset();boolean visible=false;
            for(int j=0;j<road.points.length;j++){float xx=x(road.points[j][1],lon,meters,w),yy=y(road.points[j][0],lat,meters,h,header);if(xx>-20&&xx<w+20&&yy>header-20&&yy<h+20)visible=true;if(j==0)path.moveTo(xx,yy);else path.lineTo(xx,yy);}
            if(!visible)continue;p.setStyle(Paint.Style.STROKE);p.setColor(ROAD);p.setStrokeWidth(road.kind.contains("primary")?10:road.kind.contains("secondary")?7:4);c.drawPath(path,p);
        }
        if(route.size()>1){path.reset();for(int i=0;i<route.size();i++){double[] a=route.get(i);if(i==0)path.moveTo(x(a[1],lon,meters,w),y(a[0],lat,meters,h,header));else path.lineTo(x(a[1],lon,meters,w),y(a[0],lat,meters,h,header));}p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(13);p.setColor(Color.WHITE);c.drawPath(path,p);p.setStrokeWidth(8);p.setColor(INK);c.drawPath(path,p);}
        p.setStyle(Paint.Style.FILL);
        if(!route.isEmpty()){float dx=x(s.destLon,lon,meters,w),dy=y(s.destLat,lat,meters,h,header);p.setColor(INK);c.drawCircle(dx,dy,11,p);text(c,"D",dx-6,dy+5,15,Color.WHITE);}
        if(!route.isEmpty()&&(!s.active||s.remaining<1000)){ArrayList<RectF> occupied=new ArrayList<>();int shown=0;for(int i=0;i<s.nearbyParking.size()&&shown<3;i++)try{JSONObject lot=s.nearbyParking.get(i);double a=lot.getDouble("lat"),b=lot.getDouble("lon");if(GeoMath.distance(a,b,s.destLat,s.destLon)>700)continue;float xx=x(b,lon,meters,w),yy=y(a,lat,meters,h,header);if(xx<24||xx>w-24||yy<header+22||yy>h-35)continue;String price=lot.optString("price_label","");long age=System.currentTimeMillis()-lot.optLong("verified_at",0);if(age<0||age>30L*24*60*60*1000)price="";p.setTextSize(18);float labelWidth=price.isEmpty()?0:Math.min(150,p.measureText(price)+14);if(xx+24+labelWidth>w-8)labelWidth=0;if(labelWidth==0)price="";RectF box=new RectF(xx-18,yy-20,xx+24+labelWidth,yy+20);boolean overlap=false;for(RectF other:occupied)if(RectF.intersects(box,other))overlap=true;if(overlap)continue;occupied.add(box);p.setColor(Color.WHITE);c.drawCircle(xx,yy,19,p);p.setColor(INK);c.drawCircle(xx,yy,16,p);text(c,"P",xx-8,yy+8,23,Color.WHITE);if(!price.isEmpty()){p.setColor(CREAM);c.drawRoundRect(new RectF(xx+17,yy-17,xx+24+labelWidth,yy+17),5,5,p);text(c,price,xx+24,yy+6,18,INK);}shown++;}catch(Exception ignored){}}
        float cx=w/2,cy=header+(h-header)*.65f;c.save();c.rotate(s.bearing,cx,cy);path.reset();path.moveTo(cx,cy-19);path.lineTo(cx-14,cy+15);path.lineTo(cx,cy+8);path.lineTo(cx+14,cy+15);path.close();p.setColor(Color.WHITE);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(5);c.drawPath(path,p);p.setColor(INK);p.setStyle(Paint.Style.FILL);c.drawPath(path,p);c.restore();c.restore();
    }
    private void grid(Canvas c,float w,float h){p.setColor(Color.rgb(190,207,199));p.setStrokeWidth(1);for(int x=0;x<w;x+=40)c.drawLine(x,0,x,h,p);for(int y=0;y<h;y+=40)c.drawLine(0,y,w,y,p);p.setStyle(Paint.Style.STROKE);p.setColor(INK);p.setStrokeWidth(4);c.drawRect(3,3,w-3,h-3,p);c.drawCircle(w/2,h/2,60,p);p.setStyle(Paint.Style.FILL);text(c,"FIT MY TFT",20,45,30,INK);text(c,"All corners visible · circle round",20,h-25,22,INK);}
}
