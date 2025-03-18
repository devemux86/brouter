package btools.router;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

import btools.codec.DataBuffers;
import btools.codec.MicroCache;
import btools.expressions.BExpressionContextWay;
import btools.mapaccess.MatchedWaypoint;
import btools.mapaccess.NodesCache;
import btools.mapaccess.OsmFile;
import btools.mapaccess.OsmLink;
import btools.mapaccess.OsmNode;
import btools.mapaccess.OsmNodesMap;
import btools.mapaccess.PhysicalFile;

public class AreaReader {

  File segmentFolder;

  public void getDirectAllData(File folder, RoutingContext rc, OsmNodeNamed wp, int maxscale, BExpressionContextWay expctxWay, OsmNogoPolygon searchRect, List<AreaInfo> ais) {
    this.segmentFolder = folder;

    int cellsize = 1000000 / 32;
    int scale = maxscale;
    int count = 0;
    int used = 0;
    for (int idxLat = -scale; idxLat <= scale; idxLat++) {
      for (int idxLon = -scale; idxLon <= scale; idxLon++) {
        if (ignoreCenter(maxscale, idxLon, idxLat)) continue;
        int tmplon = wp.ilon + cellsize * idxLon;
        int tmplat = wp.ilat + cellsize * idxLat;
        if (getDirectData(tmplon, tmplat, rc, expctxWay, searchRect, ais, maxscale > 7)) used++;
        count++;
      }
    }

  }

  public boolean getDirectData(int inlon, int inlat, RoutingContext rc, BExpressionContextWay expctxWay, OsmNogoPolygon searchRect, List<AreaInfo> ais, boolean checkBorder) {
    int lonDegree = inlon / 1000000;
    int latDegree = inlat / 1000000;
    int lonMod5 = (int) lonDegree % 5;
    int latMod5 = (int) latDegree % 5;

    int lon = (int) lonDegree - 180 - lonMod5;
    String slon = lon < 0 ? "W" + (-lon) : "E" + lon;

    int lat = (int) latDegree - 90 - latMod5;
    String slat = lat < 0 ? "S" + (-lat) : "N" + lat;
    lon = 180000000 + (int) (lon * 1000000 + 0.5);
    lat = 90000000 + (int) (lat * 1000000 + 0.5);

    String filenameBase = slon + "_" + slat;

    File file = new File(segmentFolder, filenameBase + ".rd5");
    PhysicalFile pf = null;

    long maxmem = rc.memoryclass * 1024L * 1024L; // in MB
    NodesCache nodesCache = new NodesCache(segmentFolder, expctxWay, rc.forceSecondaryData, maxmem, null, false);

    OsmNodesMap nodesMap = new OsmNodesMap();

    try {

      DataBuffers dataBuffers = new DataBuffers();
      pf = new PhysicalFile(file, dataBuffers, -1, -1);
      int div = pf.divisor;

      OsmFile osmf = new OsmFile(pf, lonDegree, latDegree, dataBuffers);
      if (osmf.hasData()) {
        int cellsize = 1000000 / div;
        int tmplon = inlon; // + cellsize * idxLon;
        int tmplat = inlat; // + cellsize * idxLat;
        int lonIdx = tmplon / cellsize;
        int latIdx = tmplat / cellsize;
        int subIdx = (latIdx - div * latDegree) * div + (lonIdx - div * lonDegree);

        int subLonIdx = (lonIdx - div * lonDegree);
        int subLatIdx = (latIdx - div * latDegree);

        OsmNogoPolygon dataRect = new OsmNogoPolygon(true);
        lon = lonDegree * 1000000;
        lat = latDegree * 1000000;
        int tmplon2 = lon + cellsize * (subLonIdx);
        int tmplat2 = lat + cellsize * (subLatIdx);
        dataRect.addVertex(tmplon2, tmplat2);

        tmplon2 = lon + cellsize * (subLonIdx + 1);
        tmplat2 = lat + cellsize * (subLatIdx);
        dataRect.addVertex(tmplon2, tmplat2);

        tmplon2 = lon + cellsize * (subLonIdx + 1);
        tmplat2 = lat + cellsize * (subLatIdx + 1);
        dataRect.addVertex(tmplon2, tmplat2);

        tmplon2 = lon + cellsize * (subLonIdx);
        tmplat2 = lat + cellsize * (subLatIdx + 1);
        dataRect.addVertex(tmplon2, tmplat2);

        // check for quadrant border
        boolean intersects = checkBorder && dataRect.intersects(searchRect.points.get(0).x, searchRect.points.get(0).y, searchRect.points.get(2).x, searchRect.points.get(2).y);
        if (!intersects && checkBorder)
          intersects = dataRect.intersects(searchRect.points.get(1).x, searchRect.points.get(1).y, searchRect.points.get(2).x, searchRect.points.get(3).y);
        if (intersects) {
          return false;
        }
        intersects = searchRect.intersects(dataRect.points.get(0).x, dataRect.points.get(0).y, dataRect.points.get(2).x, dataRect.points.get(2).y);
        if (!intersects)
          intersects = searchRect.intersects(dataRect.points.get(1).x, dataRect.points.get(1).y, dataRect.points.get(3).x, dataRect.points.get(3).y);
        if (!intersects)
          intersects = containsRect(searchRect, dataRect.points.get(0).x, dataRect.points.get(0).y, dataRect.points.get(2).x, dataRect.points.get(2).y);

        if (!intersects) {
          return false;
        }

        MicroCache segment = osmf.createMicroCache(lonIdx, latIdx, dataBuffers, expctxWay, null, true, null);

        if (segment != null /*&& segment.getDataSize()>0*/) {
          int size = segment.getSize();
          for (int i = 0; i < size; i++) {
            long id = segment.getIdForIndex(i);
            OsmNode node = new OsmNode(id);
            if (segment.getAndClear(id)) {
              node.parseNodeBody(segment, nodesMap, expctxWay);
              if (node.firstlink instanceof OsmLink) {
                for (OsmLink link = node.firstlink; link != null; link = link.getNext(node)) {
                  OsmNode nextNode = link.getTarget(node);
                  if (nextNode.firstlink == null)
                    continue; // don't care about dead ends
                  if (nextNode.firstlink.descriptionBitmap == null)
                    continue;

                  for (AreaInfo ai : ais) {
                    if (ai.polygon.isWithin(node.ilon, node.ilat)) {
                      ai.checkAreaInfo(expctxWay, node.getElev(), nextNode.firstlink.descriptionBitmap);
                      break;
                    }
                  }
                  break;
                }
              }
            }
          }
        }
        return true;
      }
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      if (pf != null)
        try {
          pf.close();
        } catch (Exception ee) {
        }
      nodesCache.close();
      nodesCache = null;
    }
    return false;
  }

  boolean ignoreCenter(int maxscale, int idxLon, int idxLat) {
    int centerScale = (int) Math.round(maxscale * .2) - 1;
    if (centerScale < 0) return false;
    if (idxLon >= -centerScale && idxLon <= centerScale &&
      idxLat >= -centerScale && idxLat <= centerScale) return true;
    return false;
  }

  /*
    in this case the polygon is 'only' a rectangle
  */
  boolean containsRect(OsmNogoPolygon searchRect, int p1x, int p1y, int p2x, int p2y) {
    return searchRect.isWithin((long) p1x, (long) p1y) &&
      searchRect.isWithin(p2x, p2y);
  }

  public void writeAreaInfo(String filename, MatchedWaypoint wp, List<AreaInfo> ais) throws Exception {
    DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(filename)));

    wp.writeToStream(dos);
    for (AreaInfo ai : ais) {
      dos.writeInt(ai.direction);
      dos.writeDouble(ai.elevStart);
      dos.writeInt(ai.ways);
      dos.writeInt(ai.greenWays);
      dos.writeInt(ai.riverWays);
      dos.writeInt(ai.elev50);
    }
    dos.close();
  }

  public void readAreaInfo(File fai, MatchedWaypoint wp, List<AreaInfo> ais) {
    DataInputStream dis = null;
    MatchedWaypoint ep = null;
    try {
      dis = new DataInputStream(new BufferedInputStream(new FileInputStream(fai)));
      ep = MatchedWaypoint.readFromStream(dis);
      if (Math.abs(ep.waypoint.ilon - wp.waypoint.ilon) > 500 &&
        Math.abs(ep.waypoint.ilat - wp.waypoint.ilat) > 500) {
        return;
      }
      if (Math.abs(ep.radius - wp.radius) > 500) {
        return;
      }
      for (int i = 0; i < 4; i++) {
        int direction = dis.readInt();
        AreaInfo ai = new AreaInfo(direction);
        ai.elevStart = dis.readDouble();
        ai.ways = dis.readInt();
        ai.greenWays = dis.readInt();
        ai.riverWays = dis.readInt();
        ai.elev50 = dis.readInt();
        ais.add(ai);
      }
    } catch (IOException e) {
      ais.clear();
    } finally {
      if (dis != null) {
        try {
          dis.close();
        } catch (IOException e) {
        }
      }
    }
  }

}
