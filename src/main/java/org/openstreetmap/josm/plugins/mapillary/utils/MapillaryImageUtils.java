package org.openstreetmap.josm.plugins.mapillary.utils;

import org.apache.commons.jcs3.access.CacheAccess;
import org.openstreetmap.josm.data.osm.INode;
import org.openstreetmap.josm.data.osm.IWay;
import org.openstreetmap.josm.data.vector.VectorNode;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.mapillary.cache.CacheUtils;
import org.openstreetmap.josm.plugins.mapillary.cache.Caches;
import org.openstreetmap.josm.plugins.mapillary.cache.MapillaryCache;
import org.openstreetmap.josm.plugins.mapillary.data.mapillary.OrganizationRecord;
import org.openstreetmap.josm.plugins.mapillary.utils.api.JsonDecoder;
import org.openstreetmap.josm.plugins.mapillary.utils.api.JsonImageDetailsDecoder;
import org.openstreetmap.josm.tools.HttpClient;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.UncheckedParseException;
import org.openstreetmap.josm.tools.date.DateUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Keys and utility methods for Mapillary Images
 */
public final class MapillaryImageUtils {
  private MapillaryImageUtils() {
    /* No op */}

  /** The base image url key pattern (v4 sizes are 256, 1024, 2048) */
  public static final Pattern BASE_IMAGE_KEY = Pattern.compile("^thumb_([0-9]+)_url$");
  // Image specific
  /** Check if the node has one of the Mapillary keys */
  public static final Predicate<INode> IS_IMAGE = node -> node != null
    && (node.hasKey(MapillaryURL.APIv4.ImageProperties.ID.toString()) || node.hasKey(MapillaryImageUtils.IMPORTED_KEY));
  /** Check if the node is for a panoramic image */
  public static final Predicate<INode> IS_PANORAMIC = node -> node != null
    && MapillaryKeys.PANORAMIC_TRUE.equals(node.get(MapillaryURL.APIv4.ImageProperties.IS_PANO.toString()));

  public static final Predicate<INode> IS_DOWNLOADABLE = node -> node != null
    && node.getKeys().keySet().stream().anyMatch(key -> BASE_IMAGE_KEY.matcher(key).matches());
  public static final String IMPORTED_KEY = "import_file";

  /**
   * A pattern to look for strings that are only numbers -- mostly used during switchover from v3 to v4 API
   *
   * @deprecated Figure out if this needs to be kept
   */
  @Deprecated
  private static final Pattern NUMBERS = Pattern.compile("\\d+");

  /**
   * Get the sequence for an image
   *
   * @param image the image to get a sequence for
   * @return The sequence, if it exists
   */
  @Nullable
  public static IWay<?> getSequence(@Nullable INode image) {
    if (image == null) {
      return null;
    }
    return image.getReferrers().stream().filter(IWay.class::isInstance).map(IWay.class::cast).findFirst().orElse(null);
  }

  /**
   * Get the date an image was created at
   *
   * @param img The image
   * @return The instant the image was created
   */
  @Nonnull
  public static Instant getDate(@Nonnull INode img) {
    if (Instant.EPOCH.equals(img.getInstant()) && !Instant.EPOCH.equals(getCapturedAt(img))) {
      try {
        Instant instant = getCapturedAt(img);
        img.setInstant(instant);
        return instant;
      } catch (NumberFormatException e) {
        Logging.error(e);
      }
    }
    return img.getInstant();
  }

  /**
   * Get the quality score for an image
   *
   * @param img The image to get the quality score for
   * @return The quality score (1, 2, 3, 4, 5, or {@link Float#MIN_VALUE})
   */
  public static float getQuality(@Nonnull INode img) {
    if (img.hasKey(MapillaryURL.APIv4.ImageProperties.QUALITY_SCORE.toString())) {
      try {
        return Float.parseFloat(img.get(MapillaryURL.APIv4.ImageProperties.QUALITY_SCORE.toString()));
      } catch (final NumberFormatException e) {
        Logging.error(e);
      }
    }
    return Float.MIN_VALUE;
  }

  /**
   * Get the angle for an image
   *
   * @param img The image to get the angle for
   * @return The angle (radians), or {@link Double#NaN}.
   */
  public static double getAngle(@Nonnull INode img) {
    if (Boolean.TRUE.equals(MapillaryProperties.USE_COMPUTED_LOCATIONS.get())
      && img.hasKey(MapillaryURL.APIv4.ImageProperties.COMPUTED_COMPASS_ANGLE.toString())) {
      return Math
        .toRadians(Double.parseDouble(img.get(MapillaryURL.APIv4.ImageProperties.COMPUTED_COMPASS_ANGLE.toString())));
    }
    return img.hasKey(MapillaryURL.APIv4.ImageProperties.COMPASS_ANGLE.toString())
      ? Math.toRadians(Double.parseDouble(img.get(MapillaryURL.APIv4.ImageProperties.COMPASS_ANGLE.toString())))
      : Double.NaN;
  }

  /**
   * Get the file for the image
   *
   * @param img The image to get the file for
   * @return The image file. May be {@code null}.
   */
  @Nullable
  public static File getFile(@Nonnull INode img) {
    return img.hasKey(IMPORTED_KEY) ? new File(img.get(IMPORTED_KEY)) : null;
  }

  /**
   * Get a future for an image
   *
   * @param image The node with image information
   * @return The future with a potential image (image may be {@code null})
   */
  @Nonnull
  public static Future<BufferedImage> getImage(@Nonnull INode image) {
    // TODO use URL field in v4
    if (MapillaryImageUtils.IS_DOWNLOADABLE.test(image)) {
      CompletableFuture<BufferedImage> completableFuture = new CompletableFuture<>();
      CacheUtils.submit(image, MapillaryCache.Type.FULL_IMAGE, (entry, attributes, result) -> {
        try {
          BufferedImage realImage = ImageIO.read(new ByteArrayInputStream(entry.getContent()));
          completableFuture.complete(realImage);
        } catch (IOException e) {
          Logging.error(e);
          completableFuture.complete(null);
        }
      });
      return completableFuture;
    } else if (image.hasKey(IMPORTED_KEY)) {
      return MainApplication.worker.submit(() -> ImageIO.read(new File(image.get(IMPORTED_KEY))));
    }
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Get the captured at time
   *
   * @param image The image to get the captured at time
   * @return The time the image was captured at, or {@link Instant#EPOCH} if not known.
   */
  @Nonnull
  private static Instant getCapturedAt(@Nonnull INode image) {
    String time = "";
    if (image.hasKey(MapillaryURL.APIv4.ImageProperties.CAPTURED_AT.toString())) {
      time = image.get(MapillaryURL.APIv4.ImageProperties.CAPTURED_AT.toString());
    }
    if (NUMBERS.matcher(time).matches()) {
      return Instant.ofEpochMilli(Long.parseLong(time));
    } else if (!"".equals(time)) {
      try {
        return DateUtils.parseInstant(time);
      } catch (UncheckedParseException e) {
        Logging.error(e);
      }
    }
    return Instant.EPOCH;
  }

  /**
   * Get The key for a node
   *
   * @param image The image
   * @return The key, or {@code null} if no key exists
   */
  @Nullable
  public static String getKey(@Nullable INode image) {
    if (image != null && image.hasKey(MapillaryURL.APIv4.ImageProperties.ID.toString())) {
      return image.get(MapillaryURL.APIv4.ImageProperties.ID.toString());
    }
    return null;
  }

  /**
   * Get the sequence key
   *
   * @param image The image with a sequence key
   * @return The sequence key or {@code null} if no sequence key exists
   */
  @Nullable
  public static String getSequenceKey(@Nullable INode image) {
    if (image != null) {
      if (image.hasKey(MapillaryURL.APIv4.ImageProperties.SEQUENCE.toString())) {
        return image.get(MapillaryURL.APIv4.ImageProperties.SEQUENCE.toString());
      } else if (image.hasKey(MapillaryURL.APIv4.ImageProperties.SEQUENCE_ID.toString())) {
        return image.get(MapillaryURL.APIv4.ImageProperties.SEQUENCE_ID.toString());
      }
    }
    return null;
  }

  /**
   * Download image details
   *
   * @param images The image details to download
   */
  public static void downloadImageDetails(@Nonnull Collection<VectorNode> images) {
    downloadImageDetails(images.toArray(new VectorNode[0]));
  }

  /**
   * Download additional image details
   *
   * @param images The image(s) to get additional details for
   */
  public static void downloadImageDetails(@Nonnull VectorNode... images) {
    MapillaryUtils.getForkJoinPool().execute(() -> {
      final String[] keys = Stream.of(images).filter(Objects::nonNull).map(MapillaryImageUtils::getKey)
        .filter(key -> !"".equals(key)).toArray(String[]::new);
      downloadImageDetails(keys);
    });
  }

  /**
   * Get image details for some specific keys
   *
   * @param keys the keys to get details for
   */
  private static void downloadImageDetails(@Nonnull String... keys) {
    Objects.requireNonNull(keys, "Image keys cannot be null");
    final CacheAccess<String, String> cache = Caches.metaDataCache;
    for (String key : keys) {
      final String imageUrl = MapillaryURL.APIv4.getImageInformation(key);
      final String cacheData = cache.get(imageUrl, () -> {
        final HttpClient client;
        final HttpClient.Response response;
        try {
          client = HttpClient.create(new URL(imageUrl));
          response = client.connect();
        } catch (IOException e) {
          Logging.error(e);
          return null;
        }
        try (BufferedReader reader = response.getContentReader(); JsonReader jsonReader = Json.createReader(reader)) {
          JsonObject object = jsonReader.readObject();
          return object.toString();
        } catch (IOException e) {
          Logging.error(e);
        }
        return null;
      });
      try (
        JsonReader reader = Json.createReader(new ByteArrayInputStream(cacheData.getBytes(StandardCharsets.UTF_8)))) {
        JsonDecoder.decodeData(reader.readObject(), JsonImageDetailsDecoder::decodeImageInfos);
      }
    }
  }

  /**
   * Get the organization for an image
   *
   * @param img The image to get an organization for
   * @return The organization (never null, may be {@link OrganizationRecord#NULL_RECORD}).
   */
  @Nonnull
  public static OrganizationRecord getOrganization(@Nullable INode img) {
    if (img != null) {
      final String organizationKey = MapillaryURL.APIv4.ImageProperties.ORGANIZATION_ID.toString();
      if (img.hasKey(organizationKey)) {
        return OrganizationRecord.getOrganization(img.get(organizationKey));
      }
      IWay<?> sequence = getSequence(img);
      if (sequence != null && sequence.hasKey(organizationKey)) {
        return OrganizationRecord.getOrganization(sequence.get(organizationKey));
      }
    }
    return OrganizationRecord.NULL_RECORD;
  }
}
