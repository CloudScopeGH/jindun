/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.volley.request;

import android.annotation.TargetApi;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;

import com.android.ui.drawable.AlphableBitmapDrawable;
import com.android.ui.drawable.RecyclingBitmapDrawable;
import com.android.volley.DefaultRetryPolicy;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyLog;
import com.android.volley.error.ParseError;
import com.android.volley.misc.ImageUtils;
import com.android.volley.misc.Utils;
import com.android.volley.toolbox.HttpHeaderParser;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * A canned request for getting an image at a given URL and calling
 * back with a decoded Bitmap.
 */
public class ImageRequest extends Request<BitmapDrawable> {
    /**
     * Socket timeout in milliseconds for image requests
     */
    private static final int IMAGE_TIMEOUT_MS = 1000;

    /**
     * Default number of retries for image requests
     */
    private static final int IMAGE_MAX_RETRIES = 2;

    /**
     * Default backoff multiplier for image requests
     */
    private static final float IMAGE_BACKOFF_MULT = 2f;

    private static final boolean PREFER_QUALITY_OVER_SPEED = false;

    private final Response.Listener<BitmapDrawable> mListener;
    private final Config mDecodeConfig;
    private final int mMaxWidth;
    private final int mMaxHeight;
    private final boolean mNeedStateChanges;
    private Resources mResources;

    /**
     * Decoding lock so that we don't decode more than one image at a time (to avoid OOM's)
     */
    private static final Object sDecodeLock = new Object();

    private final BitmapFactory.Options defaultOptions;

    /**
     * Creates a new image request, decoding to a maximum specified width and
     * height. If both width and height are zero, the image will be decoded to
     * its natural size. If one of the two is nonzero, that dimension will be
     * clamped and the other one will be set to preserve the image's aspect
     * ratio. If both width and height are nonzero, the image will be decoded to
     * be fit in the rectangle of dimensions width x height while keeping its
     * aspect ratio.
     *
     * @param url           URL of the image
     * @param resources     {@link Resources} reference for parsing resource URIs. Can be
     *                      <code>null</code> if you don't need to load resource uris
     * @param listener      Listener to receive the decoded bitmap
     * @param maxWidth      Maximum width to decode this bitmap to, or zero for none
     * @param maxHeight     Maximum height to decode this bitmap to, or zero for
     *                      none
     * @param decodeConfig  Format to decode the bitmap to
     * @param errorListener Error listener, or null to ignore errors
     */
    public ImageRequest(String url, Resources resources,
                        Response.Listener<BitmapDrawable> listener, int maxWidth, int maxHeight,
                        Config decodeConfig, boolean needStateChanges, Response.ErrorListener errorListener) {
        super(Method.GET, url, errorListener);
        setRetryPolicy(
                new DefaultRetryPolicy(IMAGE_TIMEOUT_MS, IMAGE_MAX_RETRIES, IMAGE_BACKOFF_MULT));

        mResources = resources;
        mListener = listener;
        mDecodeConfig = decodeConfig;
        mMaxWidth = maxWidth;
        mMaxHeight = maxHeight;
        mNeedStateChanges = needStateChanges;
        defaultOptions = getDefaultOptions();
    }

    @Override
    public Priority getPriority() {
        return Priority.LOW;
    }

    /**
     * Scales one side of a rectangle to fit aspect ratio.
     *
     * @param maxPrimary      Maximum size of the primary dimension (i.e. width for
     *                        max width), or zero to maintain aspect ratio with secondary
     *                        dimension
     * @param maxSecondary    Maximum size of the secondary dimension, or zero to
     *                        maintain aspect ratio with primary dimension
     * @param actualPrimary   Actual size of the primary dimension
     * @param actualSecondary Actual size of the secondary dimension
     */
    public static int getResizedDimension(int maxPrimary, int maxSecondary, int actualPrimary,
                                          int actualSecondary) {
        // If no dominant value at all, just return the actual.
        if (maxPrimary == 0 && maxSecondary == 0) {
            return actualPrimary;
        }

        // If primary is unspecified, scale primary to match secondary's scaling ratio.
        if (maxPrimary == 0) {
            double ratio = (double) maxSecondary / (double) actualSecondary;
            return (int) (actualPrimary * ratio);
        }

        if (maxSecondary == 0) {
            return maxPrimary;
        }

        double ratio = (double) actualSecondary / (double) actualPrimary;
        int resized = maxPrimary;
        if (resized * ratio > maxSecondary) {
            resized = (int) (maxSecondary / ratio);
        }
        return resized;
    }

    @Override
    protected Response<BitmapDrawable> parseNetworkResponse(NetworkResponse response) {
        // Serialize all decode on a global lock to reduce concurrent heap usage.
        synchronized (sDecodeLock) {
            try {
                String url = getUrl();
                if (url.startsWith("http")) {
                    return doParse(response);
                } else if (url.startsWith("file:")) {
                    return doFileParse();
//                }else if (url.startsWith("video:")) {
//                    return doVideoFileParse();
                } else if (url.startsWith("android.resource:")) {//适合解析大图片
                    return doResourceParse();
                } else {//优先判断http 命中率最高
                    return doParse(response);
                }
            } catch (OutOfMemoryError e) {
                VolleyLog.e("Caught OOM for %d byte image, url=%s", response.data.length, getUrl());
                return Response.error(new ParseError(e));
            }
        }
    }

    /**
     * The real guts of parseNetworkResponse. Broken out for readability.
     *
     * This version is for reading a Bitmap from file
     private Response<BitmapDrawable> doVideoFileParse() {

     final String requestUrl = getUrl();
     // Remove the 'video://' prefix
     File bitmapFile = new File(requestUrl.substring(8, requestUrl.length()));

     if (!bitmapFile.exists() || !bitmapFile.isFile()) {
     return Response.error(new ParseError(new FileNotFoundException(
     String.format("File not found: %s",
     bitmapFile.getAbsolutePath()))));
     }

     BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
     decodeOptions.inInputShareable = true;
     decodeOptions.inPurgeable = true;
     decodeOptions.inPreferredConfig = mDecodeConfig;
     Bitmap bitmap = null;
     if (mMaxWidth == 0 && mMaxHeight == 0) {

     bitmap = getVideoFrame(bitmapFile.getAbsolutePath());//BitmapFactory.decodeFile(bitmapFile.getAbsolutePath(), decodeOptions);
     addMarker("read-full-size-image-from-file");
     } else {
     // If we have to resize this image, first get the natural bounds.
     decodeOptions.inJustDecodeBounds = true;
     //BitmapFactory.decodeFile(bitmapFile.getAbsolutePath(), decodeOptions);
     int actualWidth = decodeOptions.outWidth;
     int actualHeight = decodeOptions.outHeight;

     // Then compute the dimensions we would ideally like to decode to.
     int desiredWidth = getResizedDimension(mMaxWidth, mMaxHeight,
     actualWidth, actualHeight);
     int desiredHeight = getResizedDimension(mMaxHeight, mMaxWidth,
     actualHeight, actualWidth);

     // Decode to the nearest power of two scaling factor.
     decodeOptions.inJustDecodeBounds = false;
     decodeOptions.inSampleSize = ImageUtils.findBestSampleSize(actualWidth, actualHeight, desiredWidth, desiredHeight);
     Bitmap tempBitmap = getVideoFrame(bitmapFile.getAbsolutePath());//BitmapFactory.decodeFile(bitmapFile.getAbsolutePath(), decodeOptions);
     addMarker(String.format("read-from-file-scaled-times-%d",
     decodeOptions.inSampleSize));
     // If necessary, scale down to the maximal acceptable size.
     if (tempBitmap != null
     && (tempBitmap.getWidth() > desiredWidth || tempBitmap.getHeight() > desiredHeight)) {
     bitmap = Bitmap.createScaledBitmap(tempBitmap, desiredWidth,
     desiredHeight, true);
     tempBitmap.recycle();
     addMarker("scaling-read-from-file-bitmap");
     } else {
     bitmap = tempBitmap;
     }

     }

     if (bitmap == null) {
     return Response.error(new ParseError());
     } else {
     BitmapDrawable drawable;
     if (Utils.hasHoneycomb()) {
     // Running on Honeycomb or newer, so wrap in a standard BitmapDrawable
     drawable = new BitmapDrawable(mResources, bitmap);
     } else {
     // Running on Gingerbread or older, so wrap in a RecyclingBitmapDrawable
     // which will recycle automagically
     drawable = new RecyclingBitmapDrawable(mResources, bitmap);
     }
     return Response.success(drawable, HttpHeaderParser.parseBitmapCacheHeaders(bitmap));
     }
     }

     private Bitmap getVideoFrame(String path) {
     return ThumbnailUtils.createVideoThumbnail(path, MediaStore.Images.Thumbnails.MINI_KIND);
     *//*		MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
			retriever.setDataSource(path);
			return retriever.getFrameAtTime();
		} catch (IllegalArgumentException ex) {
			ex.printStackTrace();
		} catch (RuntimeException ex) {
			ex.printStackTrace();
		} finally {
			try {
				retriever.release();
			} catch (RuntimeException ex) {
			}
		}
		return null;*//*
	}*/

    /**
     * The real guts of parseNetworkResponse. Broken out for readability.
     * <p/>
     * This version is for reading a Bitmap from file
     */
    private Response<BitmapDrawable> doFileParse() {
        final String requestUrl = getUrl();
        // Remove the 'file://' prefix
        File bitmapFile = new File(requestUrl.substring(7, requestUrl.length()));

        if (!bitmapFile.exists() || !bitmapFile.isFile()) {
            return Response.error(new ParseError(new FileNotFoundException(
                    String.format("File not found: %s",
                            bitmapFile.getAbsolutePath()))));
        }

        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inInputShareable = true;
        decodeOptions.inPurgeable = true;
        decodeOptions.inPreferredConfig = mDecodeConfig;
        Bitmap bitmap = null;
        if (mMaxWidth == 0 && mMaxHeight == 0) {

            bitmap = BitmapFactory.decodeFile(bitmapFile.getAbsolutePath(), decodeOptions);
            addMarker("read-full-size-image-from-file");
        } else {
            // If we have to resize this image, first get the natural bounds.
            decodeOptions.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(bitmapFile.getAbsolutePath(), decodeOptions);
            int actualWidth = decodeOptions.outWidth;
            int actualHeight = decodeOptions.outHeight;

            // Then compute the dimensions we would ideally like to decode to.
            int desiredWidth = getResizedDimension(mMaxWidth, mMaxHeight,
                    actualWidth, actualHeight);
            int desiredHeight = getResizedDimension(mMaxHeight, mMaxWidth,
                    actualHeight, actualWidth);

            // Decode to the nearest power of two scaling factor.
            decodeOptions.inJustDecodeBounds = false;
            decodeOptions.inSampleSize = ImageUtils.findBestSampleSize(actualWidth, actualHeight, desiredWidth, desiredHeight);
            Bitmap tempBitmap = BitmapFactory.decodeFile(bitmapFile.getAbsolutePath(), decodeOptions);
            addMarker(String.format("read-from-file-scaled-times-%d",
                    decodeOptions.inSampleSize));
            // If necessary, scale down to the maximal acceptable size.
            if (tempBitmap != null
                    && (tempBitmap.getWidth() > desiredWidth || tempBitmap.getHeight() > desiredHeight)) {
                bitmap = Bitmap.createScaledBitmap(tempBitmap, desiredWidth,
                        desiredHeight, true);
                tempBitmap.recycle();
                addMarker("scaling-read-from-file-bitmap");
            } else {
                bitmap = tempBitmap;
            }
        }

        if (bitmap == null) {
            return Response.error(new ParseError());
        } else {
            BitmapDrawable drawable;
            if (Utils.hasHoneycomb()) {
                // Running on Honeycomb or newer, so wrap in a standard BitmapDrawable
                drawable = new AlphableBitmapDrawable(mResources, bitmap, mNeedStateChanges);
            } else {
                // Running on Gingerbread or older, so wrap in a RecyclingBitmapDrawable
                // which will recycle automagically
                drawable = new RecyclingBitmapDrawable(mResources, bitmap);
            }
            //Local file no need parse Header info
            return Response.success(drawable, HttpHeaderParser.parseBitmapCacheHeaders(bitmap));
        }
    }

    /**
     * The real guts of parseNetworkResponse. Broken out for readability.
     * "android.resource://[package]/[res id]"
     * Uri path = Uri.parse("android.resource://com.androidbook.samplevideo/" + R.raw.id);
     * This version is for reading a Bitmap from resource
     */
    private Response<BitmapDrawable> doResourceParse() {
        if (mResources == null) {
            return Response.error(new ParseError());//"Resources instance is null"));
        }
        final String requestUrl = getUrl();
        final int resourceId = Integer.valueOf(Uri.parse(requestUrl).getLastPathSegment());

        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inInputShareable = true;
        decodeOptions.inPurgeable = true;
        decodeOptions.inPreferredConfig = mDecodeConfig;
        Bitmap bitmap = null;
        if (mMaxWidth == 0 && mMaxHeight == 0) {

            bitmap = BitmapFactory.decodeResource(mResources, resourceId,
                    decodeOptions);
            addMarker("read-full-size-image-from-resource");
        } else {
            // If we have to resize this image, first get the natural bounds.
            decodeOptions.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(mResources, resourceId, decodeOptions);
            int actualWidth = decodeOptions.outWidth;
            int actualHeight = decodeOptions.outHeight;

            // Then compute the dimensions we would ideally like to decode to.
            int desiredWidth = getResizedDimension(mMaxWidth, mMaxHeight, actualWidth, actualHeight);
            int desiredHeight = getResizedDimension(mMaxHeight, mMaxWidth, actualHeight, actualWidth);

            // Decode to the nearest power of two scaling factor.
            decodeOptions.inJustDecodeBounds = false;

            decodeOptions.inSampleSize = ImageUtils.findBestSampleSize(actualWidth, actualHeight, desiredWidth, desiredHeight);
            Bitmap tempBitmap = BitmapFactory.decodeResource(mResources, resourceId, decodeOptions);
            addMarker(String.format("read-from-resource-scaled-times-%d", decodeOptions.inSampleSize));
            // If necessary, scale down to the maximal acceptable size.
            if (tempBitmap != null && (tempBitmap.getWidth() > desiredWidth || tempBitmap.getHeight() > desiredHeight)) {
                bitmap = Bitmap.createScaledBitmap(tempBitmap, desiredWidth, desiredHeight, true);
                tempBitmap.recycle();
                addMarker("scaling-read-from-resource-bitmap");
            } else {
                bitmap = tempBitmap;
            }

        }

        if (bitmap == null) {
            return Response.error(new ParseError());
        } else {
            BitmapDrawable drawable;
            if (Utils.hasHoneycomb()) {
                // Running on Honeycomb or newer, so wrap in a standard BitmapDrawable
                drawable = new AlphableBitmapDrawable(mResources, bitmap, mNeedStateChanges);
            } else {
                // Running on Gingerbread or older, so wrap in a RecyclingBitmapDrawable
                // which will recycle automagically
                drawable = new RecyclingBitmapDrawable(mResources, bitmap);
            }
            //local resource no need parseHeader ,
            return Response.success(drawable, HttpHeaderParser.parseBitmapCacheHeaders(bitmap));
        }
    }

    /**
     * The real guts of parseNetworkResponse. Broken out for readability.
     */
    @TargetApi(Build.VERSION_CODES.GINGERBREAD_MR1)
    private Response<BitmapDrawable> doParse(NetworkResponse response) {
        byte[] data = response.data;
        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inInputShareable = true;
        decodeOptions.inPurgeable = true;
        decodeOptions.inPreferredConfig = mDecodeConfig;
        Bitmap bitmap = null;
        if (mMaxWidth == 0 && mMaxHeight == 0) {
            bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, decodeOptions);
            addMarker("downloaded-full-size-image");
        } else {
            // If we have to resize this image, first get the natural bounds.
            decodeOptions.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, decodeOptions);
            int actualWidth = decodeOptions.outWidth;
            int actualHeight = decodeOptions.outHeight;

            // Then compute the dimensions we would ideally like to decode to.
            int desiredWidth = getResizedDimension(mMaxWidth, mMaxHeight, actualWidth, actualHeight);
            int desiredHeight = getResizedDimension(mMaxHeight, mMaxWidth, actualHeight, actualWidth);

            // Decode to the nearest power of two scaling factor.
            decodeOptions.inJustDecodeBounds = false;

            // TODO(ficus): Do we need this or is it okay since API 8 doesn't
            // support it?
            if (Utils.hasGingerbreadMR1()) {
                decodeOptions.inPreferQualityOverSpeed = PREFER_QUALITY_OVER_SPEED;
            }

            decodeOptions.inSampleSize = ImageUtils.findBestSampleSize(actualWidth, actualHeight, desiredWidth, desiredHeight);
            Bitmap tempBitmap = BitmapFactory.decodeByteArray(data, 0, data.length, decodeOptions);

            addMarker(String.format("downloaded-scaled-times-%d", decodeOptions.inSampleSize));
            // If necessary, scale down to the maximal acceptable size.
            if (tempBitmap != null && (tempBitmap.getWidth() > desiredWidth || tempBitmap.getHeight() > desiredHeight)) {
                bitmap = Bitmap.createScaledBitmap(tempBitmap, desiredWidth, desiredHeight, true);
                tempBitmap.recycle();
                addMarker("scaling-downloaded-bitmap");
            } else {
                bitmap = tempBitmap;
            }
        }

        if (bitmap == null) {
            return Response.error(new ParseError(response));
        } else {
            BitmapDrawable drawable;
            if (Utils.hasHoneycomb()) {
                // Running on Honeycomb or newer, so wrap in a standard BitmapDrawable
                drawable = new AlphableBitmapDrawable(mResources, bitmap, mNeedStateChanges);
            } else {
                // Running on Gingerbread or older, so wrap in a RecyclingBitmapDrawable
                // which will recycle automagically
                drawable = new RecyclingBitmapDrawable(mResources, bitmap);
            }
//			bitmap TTL time will never expired.
            return Response.success(drawable, HttpHeaderParser.parseIgnoreCacheHeaders(response));
        }
    }

    @Override
    protected void deliverResponse(Response<BitmapDrawable> response) {
        if (null != mListener) {
            mListener.onResponse(response.getResult());
        }
    }

    @TargetApi(11)
    public static BitmapFactory.Options getDefaultOptions() {
        BitmapFactory.Options decodeBitmapOptions = new BitmapFactory.Options();
        decodeBitmapOptions.inDither = false;
        decodeBitmapOptions.inScaled = false;
        decodeBitmapOptions.inPreferredConfig = Bitmap.Config.RGB_565;
        decodeBitmapOptions.inSampleSize = 1;
        if (Utils.hasHoneycomb()) {
            decodeBitmapOptions.inMutable = true;
        }
        return decodeBitmapOptions;
    }

    @SuppressWarnings("unused")
    private BitmapFactory.Options getOptions() {
        BitmapFactory.Options result = new BitmapFactory.Options();
        copyOptions(defaultOptions, result);
        return result;
    }

    private static void copyOptions(BitmapFactory.Options from, BitmapFactory.Options to) {
        if (Build.VERSION.SDK_INT >= 11) {
            copyOptionsHoneycomb(from, to);
        } else if (Build.VERSION.SDK_INT >= 10) {
            copyOptionsGingerbreadMr1(from, to);
        } else {
            copyOptionsFroyo(from, to);
        }
    }

    @TargetApi(11)
    private static void copyOptionsHoneycomb(BitmapFactory.Options from, BitmapFactory.Options to) {
        copyOptionsGingerbreadMr1(from, to);
        to.inMutable = from.inMutable;
    }

    @TargetApi(10)
    private static void copyOptionsGingerbreadMr1(BitmapFactory.Options from, BitmapFactory.Options to) {
        copyOptionsFroyo(from, to);
        to.inPreferQualityOverSpeed = from.inPreferQualityOverSpeed;
    }

    private static void copyOptionsFroyo(BitmapFactory.Options from, BitmapFactory.Options to) {
        to.inDensity = from.inDensity;
        to.inDither = from.inDither;
        to.inInputShareable = from.inInputShareable;
        to.inPreferredConfig = from.inPreferredConfig;
        to.inPurgeable = from.inPurgeable;
        to.inSampleSize = from.inSampleSize;
        to.inScaled = from.inScaled;
        to.inScreenDensity = from.inScreenDensity;
        to.inTargetDensity = from.inTargetDensity;
    }
}
