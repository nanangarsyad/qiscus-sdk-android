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

package com.qiscus.sdk.data.remote;

import com.qiscus.sdk.Qiscus;
import com.qiscus.sdk.data.model.QiscusComment;
import com.qiscus.sdk.event.QiscusCommentReceivedEvent;
import com.qiscus.sdk.event.QiscusCommentResendEvent;
import com.qiscus.sdk.util.QiscusErrorLogger;

import org.greenrobot.eventbus.EventBus;

import java.io.File;

import retrofit2.HttpException;
import rx.Observable;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;

/**
 * Created on : August 22, 2017
 * Author     : zetbaitsu
 * Name       : Zetra
 * GitHub     : https://github.com/zetbaitsu
 */
final class QiscusResendCommentHelper {

    static void tryResendPendingComment() {
        Qiscus.getDataStore()
                .getObservablePendingComments()
                .flatMap(Observable::from)
                .doOnNext(QiscusResendCommentHelper::resendComment)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(commentSend -> {
                }, QiscusErrorLogger::print);
    }

    private static void resendComment(QiscusComment qiscusComment) {
        qiscusComment.setState(QiscusComment.STATE_SENDING);

        if (qiscusComment.isAttachment()) {
            resendFile(qiscusComment);
            return;
        }

        EventBus.getDefault().post(new QiscusCommentResendEvent(qiscusComment));

        QiscusApi.getInstance().postComment(qiscusComment)
                .doOnSubscribe(() -> Qiscus.getDataStore().add(qiscusComment))
                .doOnNext(QiscusResendCommentHelper::commentSuccess)
                .doOnError(throwable -> commentFail(throwable, qiscusComment))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(commentSend ->
                                EventBus.getDefault().post(new QiscusCommentReceivedEvent(commentSend)),
                        QiscusErrorLogger::print);
    }

    private static void resendFile(QiscusComment qiscusComment) {
        if (qiscusComment.getAttachmentUri().toString().startsWith("http")) { //We forward file message
            forwardFile(qiscusComment);
            return;
        }

        File file = new File(qiscusComment.getAttachmentUri().toString());
        if (!file.exists()) { //File have been removed, so we can not upload it anymore
            qiscusComment.setDownloading(false);
            qiscusComment.setState(QiscusComment.STATE_FAILED);
            Qiscus.getDataStore().addOrUpdate(qiscusComment);
            EventBus.getDefault().post(new QiscusCommentResendEvent(qiscusComment));
            return;
        }

        qiscusComment.setDownloading(true);
        qiscusComment.setProgress(0);
        EventBus.getDefault().post(new QiscusCommentResendEvent(qiscusComment));

        QiscusApi.getInstance().uploadFile(file, percentage -> qiscusComment.setProgress((int) percentage))
                .doOnSubscribe(() -> Qiscus.getDataStore().addOrUpdate(qiscusComment))
                .flatMap(uri -> {
                    qiscusComment.setMessage(String.format("[file] %s [/file]", uri.toString()));
                    return QiscusApi.getInstance().postComment(qiscusComment);
                })
                .doOnNext(commentSend -> {
                    Qiscus.getDataStore()
                            .addOrUpdateLocalPath(commentSend.getTopicId(), commentSend.getId(), file.getAbsolutePath());
                    qiscusComment.setDownloading(false);
                    commentSuccess(commentSend);
                })
                .doOnError(throwable -> commentFail(throwable, qiscusComment))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(commentSend ->
                                EventBus.getDefault().post(new QiscusCommentReceivedEvent(commentSend)),
                        QiscusErrorLogger::print);
    }

    private static void forwardFile(QiscusComment qiscusComment) {
        qiscusComment.setDownloading(true);
        qiscusComment.setProgress(100);
        EventBus.getDefault().post(new QiscusCommentResendEvent(qiscusComment));

        QiscusApi.getInstance().postComment(qiscusComment)
                .doOnSubscribe(() -> Qiscus.getDataStore().addOrUpdate(qiscusComment))
                .doOnNext(commentSend -> {
                    qiscusComment.setDownloading(false);
                    commentSuccess(commentSend);
                })
                .doOnError(throwable -> commentFail(throwable, qiscusComment))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(commentSend ->
                                EventBus.getDefault().post(new QiscusCommentReceivedEvent(commentSend)),
                        QiscusErrorLogger::print);
    }

    private static void commentSuccess(QiscusComment qiscusComment) {
        qiscusComment.setState(QiscusComment.STATE_ON_QISCUS);
        QiscusComment savedQiscusComment = Qiscus.getDataStore().getComment(qiscusComment.getId(), qiscusComment.getUniqueId());
        if (savedQiscusComment != null && savedQiscusComment.getState() > qiscusComment.getState()) {
            qiscusComment.setState(savedQiscusComment.getState());
        }
        Qiscus.getDataStore().addOrUpdate(qiscusComment);
    }

    private static void commentFail(Throwable throwable, QiscusComment qiscusComment) {
        int state = QiscusComment.STATE_PENDING;
        if (throwable instanceof HttpException) { //Error response from server
            //Means something wrong with server, e.g user is not member of these room anymore
            HttpException httpException = (HttpException) throwable;
            if (httpException.code() >= 400) {
                qiscusComment.setDownloading(false);
                state = QiscusComment.STATE_FAILED;
            }
        }

        qiscusComment.setState(state);
        QiscusComment savedQiscusComment = Qiscus.getDataStore().getComment(qiscusComment.getId(), qiscusComment.getUniqueId());
        if (savedQiscusComment != null) {
            if (savedQiscusComment.getState() < qiscusComment.getState()) {
                qiscusComment.setState(state);
                Qiscus.getDataStore().addOrUpdate(qiscusComment);
            } else {
                qiscusComment.setState(savedQiscusComment.getState());
            }
        } else {
            qiscusComment.setState(state);
            Qiscus.getDataStore().addOrUpdate(qiscusComment);
        }
    }
}
