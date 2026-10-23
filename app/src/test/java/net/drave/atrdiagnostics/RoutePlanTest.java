package net.drave.atrdiagnostics;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class RoutePlanTest {
    private static String line(double... points) {
        StringBuilder out=new StringBuilder();long lat=0,lon=0;
        for(int i=0;i<points.length;i+=2){long la=Math.round(points[i]*1e6),lo=Math.round(points[i+1]*1e6);encode(out,la-lat);encode(out,lo-lon);lat=la;lon=lo;}
        return out.toString();
    }
    private static void encode(StringBuilder out,long delta){long v=delta<0?~(delta<<1):delta<<1;while(v>=32){out.append((char)((32|(v&31))+63));v>>=5;}out.append((char)(v+63));}
    private static JSONObject leg(String shape,String street,int end,double time)throws Exception{
        return new JSONObject().put("shape",shape).put("maneuvers",new JSONArray().put(new JSONObject()
            .put("begin_shape_index",0).put("end_shape_index",end).put("time",time).put("length",1)
            .put("street_names",new JSONArray().put(street))));
    }
    private static JSONObject response(String shape,String street,double seconds)throws Exception{
        return new JSONObject().put("trip",new JSONObject().put("summary",new JSONObject().put("time",seconds))
            .put("legs",new JSONArray().put(leg(shape,street,2,seconds))));
    }
    @Test public void actualDistinctAlternativesKeepTheirOwnGeometryDirectionsAndTime()throws Exception{
        JSONObject primary=response(line(10,123,10.01,123,10.02,123),"Main Street",120);
        JSONObject alternative=response(line(10,123,10.01,123.01,10.02,123),"Coast Road",180);
        primary.put("alternates",new JSONArray().put(alternative));
        List<RoutePlan> plans=RoutePlan.options(primary);
        assertEquals(2,plans.size());assertEquals(180,plans.get(1).seconds,0);
        assertEquals("Coast Road",plans.get(1).via);assertEquals(123.01,plans.get(1).shape.get(1)[1],1e-6);
        assertTrue(plans.get(1).meters>plans.get(0).meters);
    }
    @Test public void duplicateAndMalformedAlternatesDoNotInventExtraChoices()throws Exception{
        JSONObject primary=response(line(10,123,10.01,123,10.02,123),"Main Street",120);
        JSONObject duplicate=new JSONObject(primary.toString());
        primary.put("alternates",new JSONArray().put(duplicate).put(response("broken","Broken",30)).put(new JSONObject()));
        assertEquals(1,RoutePlan.options(primary).size());
    }
    @Test public void multipleLegsAreJoinedAndManeuverIndicesRemapped()throws Exception{
        JSONArray legs=new JSONArray().put(leg(line(10,123,10.01,123),"First",1,60))
            .put(leg(line(10.01,123,10.02,123),"Second",1,90));
        RoutePlan p=RoutePlan.options(new JSONObject().put("trip",new JSONObject().put("legs",legs)
            .put("summary",new JSONObject().put("time",150).put("has_ferry",true)))).get(0);
        assertEquals(3,p.shape.size());assertEquals(1,p.maneuvers.getJSONObject(1).getInt("begin_shape_index"));
        assertEquals(2,p.maneuvers.getJSONObject(1).getInt("end_shape_index"));
        assertTrue(p.ferry);assertEquals(150,p.seconds,0);
    }
    @Test public void previewAndRerouteAlwaysRetainAccessRestrictions()throws Exception{
        for(boolean alternatives:new boolean[]{true,false}){
            JSONObject request=RouteRequest.between(10,123,10.1,123.1,alternatives);
            JSONObject options=request.getJSONObject("costing_options").getJSONObject("motor_scooter");
            assertFalse(options.optBoolean("ignore_access"));assertFalse(options.optBoolean("ignore_restrictions"));
            assertFalse(options.optBoolean("ignore_oneways"));assertEquals(alternatives?2:0,request.getInt("alternates"));
        }
    }
    @Test public void multipointRequestDoesNotRequestUnsupportedAlternatives()throws Exception{
        JSONArray stops=new JSONArray();for(int i=0;i<3;i++)stops.put(new JSONObject().put("lat",10+i*.01).put("lon",123));
        assertEquals(0,RouteRequest.through(stops,true).getInt("alternates"));
    }
}
