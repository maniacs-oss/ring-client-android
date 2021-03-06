/*
 *  Copyright (C) 2016 Savoir-faire Linux Inc.
 *
 *  Author: Thibault Wittemberg <thibault.wittemberg@savoirfairelinux.com>
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package cx.ring.services;

import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.stmt.DeleteBuilder;
import com.j256.ormlite.stmt.QueryBuilder;
import com.j256.ormlite.support.ConnectionSource;
import com.j256.ormlite.table.TableUtils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import javax.inject.Inject;
import javax.inject.Named;

import cx.ring.daemon.StringMap;
import cx.ring.model.Conference;
import cx.ring.model.Conversation;
import cx.ring.model.HistoryCall;
import cx.ring.model.HistoryEntry;
import cx.ring.model.HistoryText;
import cx.ring.model.ServiceEvent;
import cx.ring.model.SipCall;
import cx.ring.model.TextMessage;
import cx.ring.model.Uri;
import cx.ring.utils.Log;
import cx.ring.utils.Observable;

/**
 * A service managing all history related tasks.
 * Its responsabilities:
 * - inserting new entries (text or call)
 * - deleting conversations
 * - notifying Observers when history changes
 */
public abstract class HistoryService extends Observable {

    private static final String TAG = HistoryService.class.getSimpleName();

    @Inject
    @Named("ApplicationExecutor")
    protected ExecutorService mApplicationExecutor;

    protected abstract ConnectionSource getConnectionSource();

    protected abstract Dao<HistoryCall, Integer> getCallHistoryDao();

    protected abstract Dao<HistoryText, Integer> getTextHistoryDao();

    public abstract void saveVCard(String from, StringMap messages);

    public abstract void updateVCard();

    public abstract String getRelativeTimeSpanString(long lastInteraction);

    public boolean insertNewEntry(Conference toInsert) {

        for (SipCall call : toInsert.getParticipants()) {
            call.setTimestampEnd(System.currentTimeMillis());

            HistoryCall persistent = new HistoryCall(call);
            try {
                Log.d(TAG, "HistoryDao().create() " + persistent.getNumber() + " " + persistent.getStartDate().toString() + " " + persistent.getEndDate());
                getCallHistoryDao().create(persistent);
            } catch (SQLException e) {
                Log.e(TAG, "Error while inserting text conference entry", e);
                return false;
            }
        }

        // notify the observers
        setChanged();
        notifyObservers();

        return true;
    }

    private boolean insertNewTextMessage(HistoryText txt) {
        try {
            Log.d(TAG, "HistoryDao().create() id:" + txt.id + " acc:" + txt.getAccountID() + " num:" + txt.getNumber() + " date:" + txt.getDate().toString() + " msg:" + txt.getMessage());
            getTextHistoryDao().create(txt);
        } catch (SQLException e) {
            Log.e(TAG, "Error while inserting text message", e);
            return false;
        }

        // notify the observers
        setChanged();
        ServiceEvent event = new ServiceEvent(ServiceEvent.EventType.HISTORY_MODIFIED);
        notifyObservers(event);

        return true;
    }

    public boolean insertNewTextMessage(TextMessage txt) {
        HistoryText historyTxt = new HistoryText(txt);
        if (!insertNewTextMessage(historyTxt)) {
            return false;
        }
        txt.setID(historyTxt.id);

        return true;
    }

    public boolean updateTextMessage(HistoryText txt) {
        try {
            Log.d(TAG, "HistoryDao().update() id:" + txt.id + " acc:" + txt.getAccountID() + " num:"
                    + txt.getNumber() + " date:" + txt.getDate().toString() + " msg:" + txt.getMessage());
            getTextHistoryDao().update(txt);
        } catch (SQLException e) {
            Log.e(TAG, "Error while updating text message", e);
            return false;
        }

        // notify the observers
        setChanged();
        notifyObservers();

        return true;
    }

    public void getCallAndTextAsync() throws SQLException {

        mApplicationExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    List<HistoryCall> historyCalls = getAll();
                    List<HistoryText> historyTexts = getAllTextMessages();

                    ServiceEvent event = new ServiceEvent(ServiceEvent.EventType.HISTORY_LOADED);
                    event.addEventInput(ServiceEvent.EventInput.HISTORY_CALLS, historyCalls);
                    event.addEventInput(ServiceEvent.EventInput.HISTORY_TEXTS, historyTexts);
                    setChanged();
                    notifyObservers(event);
                } catch (SQLException e) {
                    Log.e(TAG, "Can't load calls and texts", e);
                }
            }
        });
    }

    private List<HistoryCall> getAll() throws SQLException {
        QueryBuilder<HistoryCall, Integer> queryBuilder = getCallHistoryDao().queryBuilder();
        queryBuilder.orderBy(HistoryCall.COLUMN_TIMESTAMP_START_NAME, true);
        return getCallHistoryDao().query(queryBuilder.prepare());
    }

    private List<HistoryText> getAllTextMessages() throws SQLException {
        QueryBuilder<HistoryText, Integer> queryBuilder = getTextHistoryDao().queryBuilder();
        queryBuilder.orderBy(HistoryText.COLUMN_TIMESTAMP_NAME, true);
        return getTextHistoryDao().query(queryBuilder.prepare());
    }

    /**
     * Removes all the text messages and call histories from the database.
     *
     * @param conversation The conversation containing the elements to delete.
     */
    public void clearHistoryForConversation(final Conversation conversation) {
        if (conversation == null) {
            Log.d(TAG, "clearHistoryForConversation: conversation is null");
            return;
        }

        mApplicationExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    Map<String, HistoryEntry> history = conversation.getRawHistory();
                    for (Map.Entry<String, HistoryEntry> entry : history.entrySet()) {
                        //~ Deleting messages
                        ArrayList<Long> textMessagesIds = new ArrayList<>(entry.getValue().getTextMessages().size());
                        for (TextMessage textMessage : entry.getValue().getTextMessages().values()) {
                            textMessagesIds.add(textMessage.getId());
                        }
                        DeleteBuilder<HistoryText, Integer> deleteTextHistoryBuilder = getTextHistoryDao()
                                .deleteBuilder();
                        deleteTextHistoryBuilder.where().in(HistoryText.COLUMN_ID_NAME, textMessagesIds);
                        deleteTextHistoryBuilder.delete();
                        //~ Deleting calls
                        ArrayList<String> callIds = new ArrayList<>(entry.getValue().getCalls().size());
                        for (HistoryCall historyCall : entry.getValue().getCalls().values()) {
                            callIds.add(historyCall.getCallId().toString());
                        }
                        DeleteBuilder<HistoryCall, Integer> deleteCallsHistoryBuilder = getCallHistoryDao()
                                .deleteBuilder();
                        deleteCallsHistoryBuilder.where().in(HistoryCall.COLUMN_CALL_ID_NAME, callIds);
                        deleteCallsHistoryBuilder.delete();
                    }

                    // notify the observers
                    setChanged();
                    ServiceEvent event = new ServiceEvent(ServiceEvent.EventType.HISTORY_MODIFIED);
                    notifyObservers(event);
                } catch (SQLException e) {
                    Log.e(TAG, "Error while clearing history for conversation", e);
                }
            }
        });

    }

    public void clearHistory() {
        try {
            TableUtils.clearTable(getConnectionSource(), HistoryCall.class);
            TableUtils.clearTable(getConnectionSource(), HistoryText.class);

            // notify the observers
            setChanged();
            notifyObservers();
        } catch (SQLException e) {
            Log.e(TAG, "Error while clearing history tables", e);
        }
    }


    public void incomingMessage(String accountId, String callId, String from, StringMap messages) {
        saveVCard(from, messages);

        String msg = null;
        final String textPlainMime = "text/plain";
        if (null != messages && messages.has_key(textPlainMime)) {
            msg = messages.getRaw(textPlainMime).toJavaString();
        }
        if (msg == null) {
            return;
        }

        TextMessage txt = new TextMessage(true, msg, new Uri(from), null, accountId);
        Log.w(TAG, "New text messsage " + txt.getAccount() + " " + txt.getCallId() + " " + txt.getMessage());

        insertNewTextMessage(txt);

        ServiceEvent event = new ServiceEvent(ServiceEvent.EventType.INCOMING_MESSAGE);
        event.addEventInput(ServiceEvent.EventInput.MESSAGE, txt);
        event.addEventInput(ServiceEvent.EventInput.CALL_ID, callId);
        setChanged();
        notifyObservers(event);
    }
}