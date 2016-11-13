// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.mapillary.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.plugins.mapillary.MapillaryImage;
import org.openstreetmap.josm.plugins.mapillary.MapillaryLayer;
import org.openstreetmap.josm.plugins.mapillary.MapillaryLocationChangeset;
import org.openstreetmap.josm.plugins.mapillary.MapillaryPlugin;
import org.openstreetmap.josm.plugins.mapillary.oauth.OAuthUtils;
import org.openstreetmap.josm.plugins.mapillary.utils.MapillaryURL;
import org.openstreetmap.josm.plugins.mapillary.utils.MapillaryUtils;
import org.openstreetmap.josm.plugins.mapillary.utils.PluginState;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Imports a set of picture files into JOSM. They must be in jpg or png format.
 */
public class MapillarySubmitCurrentChangesetAction extends JosmAction {

  private static final long serialVersionUID = 4995924098228082806L;
  private static final Log LOGGER = LogFactory.getLog(MapillarySubmitCurrentChangesetAction.class);

  /**
   * Main constructor.
   */
  public MapillarySubmitCurrentChangesetAction() {
    super(
      tr("Submit changeset"),
      MapillaryPlugin.getProvider("icon24.png"),
      tr("Submit the current changeset"),
      Shortcut.registerShortcut(
        "Submit changeset to Mapillary", tr("Submit the current changeset to Mapillary"),
        KeyEvent.CHAR_UNDEFINED, Shortcut.NONE
      ),
      false,
      "mapillarySubmitChangeset",
      false
    );
    this.setEnabled(false);
  }

  @Override
  public void actionPerformed(ActionEvent event) {
    String token = OAuthUtils.PROP_ACCESS_TOKEN.get();
    if (token == null || token.trim().isEmpty()) {
      PluginState.notLoggedInToMapillaryDialog();
      return;
    }
    PluginState.setSubmittingChangeset(true);
    MapillaryUtils.updateHelpText();
    HttpClientBuilder builder = HttpClientBuilder.create();
    HttpPost httpPost = new HttpPost(MapillaryURL.submitChangesetURL().toString());
    httpPost.addHeader("content-type", "application/json");
    httpPost.addHeader("Authorization", "Bearer " + token);
    MapillaryLocationChangeset locationChangeset = MapillaryLayer.getInstance().getLocationChangeset();
    String json = buildLocationChangesetJson(locationChangeset).build().toString();
    try (CloseableHttpClient httpClient = builder.build()) {
      httpPost.setEntity(new StringEntity(json));
      CloseableHttpResponse response = httpClient.execute(httpPost);
      if (response.getStatusLine().getStatusCode() == 200) {
        String key = Json.createReader(response.getEntity().getContent()).readObject().getString("key");
        synchronized (MapillaryUtils.class) {
          Main.map.statusLine.setHelpText(String.format("%s images submitted, Changeset key: %s", locationChangeset.size(), key));
        }
        locationChangeset.cleanChangeset();

      }

    } catch (IOException e) {
      LOGGER.error("got exception", e);
      synchronized (MapillaryUtils.class) {
        Main.map.statusLine.setHelpText("Error submitting Mapillary changeset: " + e.getMessage());
      }
    } finally {
      PluginState.setSubmittingChangeset(false);
    }
  }

  private static JsonObjectBuilder buildImgChangeJson(MapillaryImage img) {
    return Json.createObjectBuilder()
      .add("image_key", img.getKey())
      .add("values", Json.createObjectBuilder()
        .add("from", Json.createObjectBuilder()
          .add("ca", img.getCa())
          .add("lat", img.getLatLon().getY())
          .add("lon", img.getLatLon().getX())
        )
        .add("to", Json.createObjectBuilder()
          .add("ca", img.getTempCa())
          .add("lat", img.getTempLatLon().getY())
          .add("lon", img.getTempLatLon().getX())
        )
      );
  }

  private static JsonObjectBuilder buildLocationChangesetJson(MapillaryLocationChangeset changeset) {
    JsonArrayBuilder imgChanges = Json.createArrayBuilder();
    for (MapillaryImage img : changeset) {
      imgChanges.add(buildImgChangeJson(img));
    }
    return Json.createObjectBuilder()
      .add("change_type", "location")
      .add("changes", imgChanges.build())
      .add("request_comment", "JOSM-created");
  }
}
