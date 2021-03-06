/*
 *  Copyright (C) 2004-2016 Savoir-faire Linux Inc.
 *
 *  Author: Alexandre Savard <alexandre.savard@savoirfairelinux.com>
 *          Alexandre Lision <alexandre.lision@savoirfairelinux.com>
 *          Adrien Béraud <adrien.beraud@savoirfairelinux.com>
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

package cx.ring.fragments;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ListView;

import java.util.HashMap;
import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnItemClick;
import cx.ring.R;
import cx.ring.account.AccountsAdapter;
import cx.ring.account.AccountsManagementPresenter;
import cx.ring.account.AccountsManagementView;
import cx.ring.application.RingApplication;
import cx.ring.account.AccountEditionActivity;
import cx.ring.client.AccountWizard;
import cx.ring.client.HomeActivity;
import cx.ring.model.Account;
import cx.ring.mvp.BaseFragment;
import cx.ring.utils.ContentUriHandler;

public class AccountsManagementFragment extends BaseFragment<AccountsManagementPresenter> implements AccountsManagementView,
        AccountsAdapter.AccountListeners {
    static final String TAG = AccountsManagementFragment.class.getSimpleName();

    public static final int ACCOUNT_CREATE_REQUEST = 1;
    public static final int ACCOUNT_EDIT_REQUEST = 2;
    private AccountsAdapter mAccountsAdapter;

    @BindView(R.id.accounts_list)
    ListView mDnDListView;

    @BindView(R.id.empty_account_list)
    View mEmptyView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "Create Account Management Fragment");

        // dependency injection
        ((RingApplication) getActivity().getApplication()).getRingInjectionComponent().inject(this);

        mAccountsAdapter = new AccountsAdapter(this);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup parent, Bundle savedInstanceState) {
        View inflatedView = inflater.inflate(R.layout.frag_accounts_list, parent, false);
        ButterKnife.bind(this, inflatedView);
        mDnDListView.setAdapter(mAccountsAdapter);
        return inflatedView;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        setHasOptionsMenu(true);

        super.onViewCreated(view, savedInstanceState);
    }

    @OnItemClick(R.id.accounts_list)
    @SuppressWarnings("unused")
    void onItemClick(int pos) {
        presenter.clickAccount(mAccountsAdapter.getItem(pos));
    }

    @Override
    public void onResume() {
        super.onResume();
        ((HomeActivity) getActivity()).setToolbarState(true, R.string.menu_item_accounts);
        FloatingActionButton button = ((HomeActivity) getActivity()).getActionButton();
        button.setImageResource(R.drawable.ic_add_white);
        button.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                presenter.addClicked();
            }
        });
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        menu.clear();
    }

    @Override
    public void launchAccountEditActivity(Account account) {
        Log.d(TAG, "Launch account edit activity");

        Intent intent = new Intent(getActivity(), AccountEditionActivity.class)
                .setAction(Intent.ACTION_EDIT)
                .setData(Uri.withAppendedPath(ContentUriHandler.ACCOUNTS_CONTENT_URI, account.getAccountID()));
        startActivityForResult(intent, ACCOUNT_EDIT_REQUEST);
    }

    @Override
    public void launchAccountMigrationActivity(Account account) {
        Log.d(TAG, "Launch account migration activity");

        Intent intent = new Intent()
                .setClass(getActivity(), AccountWizard.class)
                .setData(Uri.withAppendedPath(ContentUriHandler.ACCOUNTS_CONTENT_URI, account.getAccountID()));
        startActivityForResult(intent, ACCOUNT_EDIT_REQUEST);
    }

    @Override
    public void launchWizardActivity() {
        Intent intent = new Intent(getActivity(), AccountWizard.class);
        startActivityForResult(intent, ACCOUNT_CREATE_REQUEST);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        presenter.refresh();
    }

    @Override
    public void refresh(final List<Account> accounts) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mAccountsAdapter.replaceAll(accounts);
                if (mAccountsAdapter.isEmpty() && mDnDListView != null) {
                    mDnDListView.setEmptyView(mEmptyView);
                }
                mAccountsAdapter.notifyDataSetChanged();
            }
        });
    }

    @Override
    public void onItemClicked(String accountId, HashMap<String, String> details) {
        presenter.itemClicked(accountId, details);
    }
}
