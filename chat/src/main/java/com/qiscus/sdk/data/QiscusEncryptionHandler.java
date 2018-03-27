/*
 * Copyright (c) 2016 Qiscus.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.qiscus.sdk.data;

import android.support.annotation.RestrictTo;
import android.util.Base64;

import com.qiscus.sdk.Qiscus;
import com.qiscus.sdk.data.encryption.QiscusE2EDataStore;
import com.qiscus.sdk.data.encryption.QiscusE2ERestApi;
import com.qiscus.sdk.data.encryption.QiscusMyBundleCache;
import com.qiscus.sdk.data.encryption.core.BundlePublic;
import com.qiscus.sdk.data.encryption.core.BundlePublicCollection;
import com.qiscus.sdk.data.encryption.core.HashId;
import com.qiscus.sdk.data.encryption.core.SesameConversation;
import com.qiscus.sdk.data.encryption.core.SesameSenderDevice;
import com.qiscus.sdk.data.model.QiscusAccount;
import com.qiscus.sdk.data.model.QiscusComment;
import com.qiscus.sdk.util.QiscusErrorLogger;
import com.qiscus.sdk.util.QiscusRawDataExtractor;

import org.json.JSONObject;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import rx.schedulers.Schedulers;

/**
 * Created on : March 01, 2018
 * Author     : zetbaitsu
 * Name       : Zetra
 * GitHub     : https://github.com/zetbaitsu
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class QiscusEncryptionHandler {
    private QiscusEncryptionHandler() {

    }

    public static void initKeyPair() throws Exception {
        if (Qiscus.hasSetupUser() && Qiscus.getChatConfig().isEnableEndToEndEncryption()) {
            if (QiscusMyBundleCache.getInstance().getSenderDevice() != null) {
                return;
            }

            String userId = Qiscus.getQiscusAccount().getEmail();
            String deviceId = QiscusMyBundleCache.getInstance().getDeviceId();
            saveSenderDevice(new SesameSenderDevice(new HashId(deviceId.getBytes()), userId));
        }
    }

    public static void encrypt(String recipientId, QiscusComment comment) {
        comment.setMessage(encrypt(recipientId, comment.getMessage()));
    }

    public static String encrypt(String recipientId, String message) {
        try {
            SesameConversation conversation = createConversation(recipientId);
            conversation.initializeSender();
            byte[] encrypted = conversation.encrypt(message.getBytes());
            return Base64.encodeToString(encrypted, Base64.DEFAULT);
        } catch (Exception e) {
            e.printStackTrace();
            return message;
        }
    }

    public static String encrypt(String recipientId, String rawType, JSONObject payload) {
        try {
            switch (rawType) {
                case "reply":
                    payload.put("text", encrypt(recipientId, payload.optString("text")));
                    break;
                case "file_attachment":
                    payload.put("url", encrypt(recipientId, payload.optString("url")));
                    payload.put("file_name", encrypt(recipientId, payload.optString("file_name")));
                    payload.put("caption", encrypt(recipientId, payload.optString("caption")));
                    break;
                case "contact_person":
                    payload.put("name", encrypt(recipientId, payload.optString("name")));
                    payload.put("value", encrypt(recipientId, payload.optString("value")));
                    break;
                case "location":
                    payload.put("name", encrypt(recipientId, payload.optString("name")));
                    payload.put("address", encrypt(recipientId, payload.optString("address")));
                    payload.put("latitude", encrypt(recipientId, payload.optString("latitude")));
                    payload.put("longitude", encrypt(recipientId, payload.optString("longitude")));
                    payload.put("map_url", encrypt(recipientId, payload.optString("map_url")));
                    break;
                case "custom":
                    payload.put("content", encrypt(recipientId, payload.optJSONObject("content").toString()));
                    break;
            }
        } catch (Exception ignored) {
            // Ignored
        }
        return payload.toString();
    }

    private static boolean decryptAble(QiscusComment comment) {
        String rawType = comment.getRawType();
        return rawType.equals("text")
                || rawType.equals("reply")
                || rawType.equals("file_attachment")
                || rawType.equals("contact_person")
                || rawType.equals("location")
                || rawType.equals("custom");
    }

    public static void decrypt(QiscusComment comment) {
        if (!decryptAble(comment)) {
            return;
        }

        //Don't decrypt if we already have it
        QiscusComment decryptedComment = Qiscus.getDataStore().getComment(comment.getUniqueId());
        if (decryptedComment != null) {
            comment.setMessage(decryptedComment.getMessage());
            comment.setExtraPayload(decryptedComment.getExtraPayload());
            return;
        }

        //We only can decrypt opponent's comment
        if (comment.isMyComment()) {
            return;
        }

        //Decrypt message
        comment.setMessage(decrypt(comment.getSenderEmail(), comment.getMessage()));

        //Decrypt payload
        if (!comment.getRawType().equals("text")) {
            try {
                comment.setExtraPayload(decrypt(comment.getSenderEmail(), comment.getRawType(),
                        new JSONObject(comment.getExtraPayload())));
            } catch (Exception ignored) {
                // ignored
            }
        }

        //We need to update payload with saved comment
        if (comment.getType() == QiscusComment.Type.REPLY) {
            try {
                JSONObject payload = QiscusRawDataExtractor.getPayload(comment);
                QiscusComment savedRepliedComment = Qiscus.getDataStore()
                        .getComment(payload.optLong("replied_comment_id"));
                if (savedRepliedComment != null) {
                    payload.put("replied_comment_message", savedRepliedComment.getMessage());
                    payload.put("replied_comment_payload", new JSONObject(savedRepliedComment.getExtraPayload()));
                    comment.setExtraPayload(payload.toString());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static String decrypt(String senderId, String rawType, JSONObject payload) {
        try {
            switch (rawType) {
                case "reply":
                    payload.put("text", decrypt(senderId, payload.optString("text")));
                    break;
                case "file_attachment":
                    payload.put("url", decrypt(senderId, payload.optString("url")));
                    payload.put("file_name", decrypt(senderId, payload.optString("file_name")));
                    payload.put("caption", decrypt(senderId, payload.optString("caption")));
                    break;
                case "contact_person":
                    payload.put("name", decrypt(senderId, payload.optString("name")));
                    payload.put("value", decrypt(senderId, payload.optString("value")));
                    break;
                case "location":
                    payload.put("name", decrypt(senderId, payload.optString("name")));
                    payload.put("address", decrypt(senderId, payload.optString("address")));
                    payload.put("map_url", decrypt(senderId, payload.optString("map_url")));
                    payload.put("latitude", Double.parseDouble(decrypt(senderId, payload.optString("latitude"))));
                    payload.put("longitude", Double.parseDouble(decrypt(senderId, payload.optString("longitude"))));
                    break;
                case "custom":
                    payload.put("content", new JSONObject(decrypt(senderId, payload.optString("content"))));
                    break;
            }
        } catch (Exception ignored) {
            // Ignored
        }
        return payload.toString();
    }

    public static void decrypt(List<QiscusComment> comments) {
        QiscusAccount account = Qiscus.getQiscusAccount();
        Map<String, List<QiscusComment>> needToDecrypt = new HashMap<>();

        for (QiscusComment comment : comments) {
            if (!comment.getRawType().equals("text") && !comment.getRawType().equals("reply")) {
                continue;
            }

            //Don't decrypt if we already have it
            QiscusComment decryptedComment = Qiscus.getDataStore().getComment(comment.getUniqueId());
            if (decryptedComment != null) {
                comment.setMessage(decryptedComment.getMessage());
                continue;
            }

            if (!comment.getSenderEmail().equals(account.getEmail())) {
                String userId = comment.getSenderEmail();
                if (!needToDecrypt.containsKey(userId)) {
                    needToDecrypt.put(userId, new ArrayList<>());
                }
                needToDecrypt.get(userId).add(comment);
            }
        }

        for (String userId : needToDecrypt.keySet()) {
            BundlePublicCollection bundle = getBundle(userId);
            for (QiscusComment comment : needToDecrypt.get(userId)) {
                try {
                    byte[] unpackedData = unpackData(comment.getMessage());
                    if (unpackedData == null) {
                        continue;
                    }

                    int index = comments.indexOf(comment);
                    if (index >= 0) {
                        SesameConversation conversation = createConversation(userId, bundle);
                        comments.get(index).setMessage(new String(conversation.decrypt(unpackedData)));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static String decrypt(String senderId, String message) {
        try {
            byte[] unpackedData = unpackData(message);
            if (unpackedData == null) {
                return message;
            }
            SesameConversation conversation = createConversation(senderId);
            byte[] decrypted = conversation.decrypt(unpackedData);
            return new String(decrypted);
        } catch (Exception e) {
            e.printStackTrace();
            return message;
        }
    }

    private static void saveSenderDevice(SesameSenderDevice senderDevice) throws Exception {
        String userId = Qiscus.getQiscusAccount().getEmail();
        String deviceId = QiscusMyBundleCache.getInstance().getDeviceId();

        //Save bundle public collection to server
        byte[] bundlePublicRaw = senderDevice.getBundle().bundlePublic.encode();

        BundlePublicCollection bundlePublicCollection = null;
        try {
            bundlePublicCollection = getBundle(userId);
        } catch (Exception e) {
            e.printStackTrace();
            QiscusErrorLogger.print(e);
        }

        if (bundlePublicCollection != null) {
            bundlePublicCollection.put(new HashId(deviceId.getBytes()), BundlePublic.decode(bundlePublicRaw));
        } else {
            bundlePublicCollection =
                    new BundlePublicCollection(new HashId(deviceId.getBytes()), BundlePublic.decode(bundlePublicRaw));
        }

        QiscusE2ERestApi.getInstance()
                .saveBundlePublicCollection(bundlePublicCollection)
                .flatMap(bundlePublicCollection1 ->
                        QiscusE2EDataStore.getInstance().saveBundlePublicCollection(userId, bundlePublicCollection1))
                .doOnNext(bundlePublicCollection1 -> QiscusMyBundleCache.getInstance().saveSenderDevice(senderDevice))
                .subscribeOn(Schedulers.io())
                .subscribe(bundlePublicCollection1 -> {
                }, QiscusErrorLogger::print);
    }

    private static byte[] unpackData(String message) throws IOException, InvalidKeyException {
        byte[] rawData = Base64.decode(message.getBytes(), Base64.DEFAULT);
        HashMap<HashId, byte[]> unpacked = SesameConversation.unpackEncrypted(rawData);
        Set<HashId> hashIds = unpacked.keySet();
        Iterator<HashId> it = hashIds.iterator();
        byte[] data = null;
        while (it.hasNext()) {
            HashId id = it.next();
            if (id.equals(QiscusMyBundleCache.getInstance().getSenderDevice().id)) {
                data = unpacked.get(id);
                break;
            }
        }
        return data;
    }

    //TODO better way to get bundle without everytime request to server
    private static BundlePublicCollection getBundle(String userId) {
        return QiscusE2EDataStore.getInstance()
                .getBundlePublicCollection(userId)
                .flatMap(bundlePublicCollection -> {
                    //Disabled to always get it from server
                    /*if (bundlePublicCollection != null) {
                        return Observable.just(bundlePublicCollection);
                    }*/
                    return QiscusE2ERestApi.getInstance()
                            .getBundlePublicCollection(userId)
                            .flatMap(bundlePublicCollection1 ->
                                    QiscusE2EDataStore.getInstance()
                                            .saveBundlePublicCollection(userId, bundlePublicCollection1));
                })
                .toBlocking()
                .first();
    }

    private static SesameConversation createConversation(String userId) throws Exception {
        return createConversation(userId, getBundle(userId));
    }

    private static SesameConversation createConversation(String userId, BundlePublicCollection bundle) throws Exception {
        QiscusAccount account = Qiscus.getQiscusAccount();
        SesameSenderDevice senderDevice = QiscusMyBundleCache.getInstance().getSenderDevice();
        return new SesameConversation(
                account.getEmail(),
                senderDevice.id,
                senderDevice.getBundle(),
                userId,
                bundle
        );
    }
}
