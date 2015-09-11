/*
 * Copyright (C) 2012-2015 The Android Money Manager Ex Project Team
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package com.money.manager.ex.adapter;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.support.v4.widget.CursorAdapter;
import android.text.Html;
import android.text.TextUtils;
import android.util.SparseBooleanArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.money.manager.ex.Constants;
import com.money.manager.ex.R;
import com.money.manager.ex.account.CalculateRunningBalanceTask;
import com.money.manager.ex.account.CalculateRunningBalanceTask2;
import com.money.manager.ex.account.CalculateRunningBalanceTask3;
import com.money.manager.ex.account.ICalculateRunningBalanceTaskCallbacks;
import com.money.manager.ex.businessobjects.AccountService;
import com.money.manager.ex.core.DateRange;
import com.money.manager.ex.core.ExceptionHandler;
import com.money.manager.ex.core.TransactionStatuses;
import com.money.manager.ex.core.TransactionTypes;
import com.money.manager.ex.currency.CurrencyService;
import com.money.manager.ex.database.QueryAllData;
import com.money.manager.ex.database.QueryBillDeposits;
import com.money.manager.ex.database.TransactionStatus;
import com.money.manager.ex.utils.DateUtils;
import com.money.manager.ex.viewmodels.AccountTransaction;

import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

/**
 * Adapter for all_data query. The list of transactions (account/recurring).
 */
public class AllDataAdapter
        extends CursorAdapter {

    public AllDataAdapter(Context context, Cursor c, TypeCursor typeCursor) {
        super(context, c, -1);

        this.mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mHeadersAccountIndex = new HashMap<>();
        mCheckedPosition = new SparseBooleanArray();
        mTypeCursor = typeCursor;
        mContext = context;

        this.requestingBalanceUpdate = new ArrayList<>();

        setFieldFromTypeCursor();
    }

    // source type: AllData or RepeatingTransaction
    public enum TypeCursor {
        ALLDATA,
        REPEATINGTRANSACTION
    }

    // type cursor
    private TypeCursor mTypeCursor = TypeCursor.ALLDATA;

    // define cursor field
    public String ID, DATE, ACCOUNTID, STATUS, AMOUNT, TRANSACTIONTYPE,
        CURRENCYID, PAYEE, ACCOUNTNAME, CATEGORY, SUBCATEGORY, NOTES,
        TOCURRENCYID, TOACCOUNTID, TOAMOUNT, TOACCOUNTNAME;

    private LayoutInflater mInflater;
    // hash map for group
    private HashMap<Integer, Integer> mHeadersAccountIndex;
    private SparseBooleanArray mCheckedPosition;
    // account and currency
    private int mAccountId = Constants.NOT_SET;
    private int mCurrencyId = Constants.NOT_SET;
    // show account name and show balance
    private boolean mShowAccountName = false;
    private boolean mShowBalanceAmount = false;
    private Context mContext;
    private HashMap<Integer, BigDecimal> balances;
    private ArrayList<TextView> requestingBalanceUpdate;

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        View view = mInflater.inflate(R.layout.item_alldata_account, parent, false);

        // holder
        AllDataViewHolder holder = new AllDataViewHolder();
        // take a pointer of object UI
        holder.linDate = (LinearLayout) view.findViewById(R.id.linearLayoutDate);
        holder.txtDay = (TextView) view.findViewById(R.id.textViewDay);
        holder.txtMonth = (TextView) view.findViewById(R.id.textViewMonth);
        holder.txtYear = (TextView) view.findViewById(R.id.textViewYear);
        holder.txtStatus = (TextView) view.findViewById(R.id.textViewStatus);
        holder.txtAmount = (TextView) view.findViewById(R.id.textViewAmount);
        holder.txtPayee = (TextView) view.findViewById(R.id.textViewPayee);
        holder.txtAccountName = (TextView) view.findViewById(R.id.textViewAccountName);
        holder.txtCategorySub = (TextView) view.findViewById(R.id.textViewCategorySub);
        holder.txtNotes = (TextView) view.findViewById(R.id.textViewNotes);
        holder.txtBalance = (TextView) view.findViewById(R.id.textViewBalance);
        // set holder to view
        view.setTag(holder);

        return view;
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        // take a holder
        AllDataViewHolder holder = (AllDataViewHolder) view.getTag();

        String transactionType = cursor.getString(cursor.getColumnIndex(TRANSACTIONTYPE));
        boolean isTransfer = TransactionTypes.valueOf(transactionType).equals(TransactionTypes.Transfer);

        // header index
        int accountId = cursor.getInt(cursor.getColumnIndex(TOACCOUNTID));
        if (!mHeadersAccountIndex.containsKey(accountId)) {
            mHeadersAccountIndex.put(accountId, cursor.getPosition());
        }

        // Status
        String status = cursor.getString(cursor.getColumnIndex(STATUS));
        holder.txtStatus.setText(TransactionStatus.getStatusAsString(mContext, status));
        // color status
        int colorBackground = TransactionStatus.getBackgroundColorFromStatus(mContext, status);
        holder.linDate.setBackgroundColor(colorBackground);
        holder.txtStatus.setTextColor(Color.GRAY);

        // date group
        try {
            Locale locale = mContext.getResources().getConfiguration().locale;

            Date date = new SimpleDateFormat(Constants.PATTERN_DB_DATE)
                    .parse(cursor.getString(cursor.getColumnIndex(DATE)));
            holder.txtMonth.setText(new SimpleDateFormat("MMM", locale).format(date));
            holder.txtYear.setText(new SimpleDateFormat("yyyy", locale).format(date));
            holder.txtDay.setText(new SimpleDateFormat("dd", locale).format(date));
        } catch (ParseException e) {
            ExceptionHandler handler = new ExceptionHandler(mContext, this);
            handler.handle(e, "parsing transaction date");
        }

        // Amount

        double amount;
        if (useDestinationValues(isTransfer, cursor)) {
            amount = cursor.getDouble(cursor.getColumnIndex(TOAMOUNT));
            setCurrencyId(cursor.getInt(cursor.getColumnIndex(TOCURRENCYID)));
        } else {
            amount = cursor.getDouble(cursor.getColumnIndex(AMOUNT));
            setCurrencyId(cursor.getInt(cursor.getColumnIndex(CURRENCYID)));
        }

        CurrencyService currencyService = new CurrencyService(mContext);
        holder.txtAmount.setText(currencyService.getCurrencyFormatted(getCurrencyId(), amount));

        // text color amount
        if (isTransfer) {
            holder.txtAmount.setTextColor(mContext.getResources().getColor(R.color.material_grey_700));
        } else if (TransactionTypes.valueOf(transactionType).equals(TransactionTypes.Deposit)) {
            holder.txtAmount.setTextColor(mContext.getResources().getColor(R.color.material_green_700));
        } else {
            holder.txtAmount.setTextColor(mContext.getResources().getColor(R.color.material_red_700));
        }

        // Group header - account name.
        if (isShowAccountName()) {
            if (mHeadersAccountIndex.containsValue(cursor.getPosition())) {
                holder.txtAccountName.setText(cursor.getString(cursor.getColumnIndex(TOACCOUNTNAME)));
                holder.txtAccountName.setVisibility(View.VISIBLE);
            } else {
                holder.txtAccountName.setVisibility(View.GONE);
            }
        } else {
            holder.txtAccountName.setVisibility(View.GONE);
        }

        // Payee
        String payee = getPayeeName(cursor, isTransfer);
        holder.txtPayee.setText(payee);

        // compose category description

        String categorySub;
        if (!isTransfer) {
            categorySub = cursor.getString(cursor.getColumnIndex(CATEGORY));
            // check sub category
            if (!(TextUtils.isEmpty(cursor.getString(cursor.getColumnIndex(SUBCATEGORY))))) {
                categorySub += " : <i>" + cursor.getString(cursor.getColumnIndex(SUBCATEGORY)) + "</i>";
            }
            // write category/subcategory format html
            if (!TextUtils.isEmpty(categorySub)) {
                // Display category/sub-category.
                categorySub = Html.fromHtml(categorySub).toString();
            } else {
                // It is either a Transfer or a split category.
                // then it is a split? todo: improve this check to make it explicit.
                categorySub = mContext.getString(R.string.split_category);
            }
        } else {
            categorySub = mContext.getString(R.string.transfer);
        }
        holder.txtCategorySub.setText(categorySub);

        // notes
        if (!TextUtils.isEmpty(cursor.getString(cursor.getColumnIndex(NOTES)))) {
            holder.txtNotes.setText(Html.fromHtml("<small>" + cursor.getString(cursor.getColumnIndex(NOTES)) + "</small>"));
            holder.txtNotes.setVisibility(View.VISIBLE);
        } else {
            holder.txtNotes.setVisibility(View.GONE);
        }
        // check if item is checked
        if (mCheckedPosition.get(cursor.getPosition(), false)) {
            view.setBackgroundResource(R.color.material_green_100);
        } else {
            view.setBackgroundResource(android.R.color.transparent);
        }

        // Display balance account or days left.
        displayBalanceAmountOrDaysLeft(holder, cursor, context);
    }

    public void clearPositionChecked() {
        mCheckedPosition.clear();
    }

//    public int getCheckedCount() {
//        return mCheckedPosition.size();
//    }

    public SparseBooleanArray getPositionsChecked() {
        return mCheckedPosition;
    }

//    public boolean getPositionChecked(int position) {
//        return mCheckedPosition.get(position);
//    }

    /**
     * Set checked in position
     */
    public void setPositionChecked(int position, boolean checked) {
        mCheckedPosition.put(position, checked);
    }

    /**
     * @return the accountId
     */
    public int getAccountId() {
        return mAccountId;
    }

    /**
     * @param mAccountId the accountId to set
     */
    public void setAccountId(int mAccountId) {
        this.mAccountId = mAccountId;
    }

    /**
     * @return the mCurrencyId
     */
    public int getCurrencyId() {
        return mCurrencyId;
    }

    /**
     * @param mCurrencyId the mCurrencyId to set
     */
    public void setCurrencyId(int mCurrencyId) {
        this.mCurrencyId = mCurrencyId;
    }

    /**
     * @return the mShowAccountName
     */
    public boolean isShowAccountName() {
        return mShowAccountName;
    }

    public void resetAccountHeaderIndexes() {
        mHeadersAccountIndex.clear();
    }

    /**
     * @param showAccountName the mShowAccountName to set
     */
    public void setShowAccountName(boolean showAccountName) {
        this.mShowAccountName = showAccountName;
    }

    /**
     * @return the mShowBalanceAmount
     */
    public boolean isShowBalanceAmount() {
        return mShowBalanceAmount;
    }

    /**
     * @param mShowBalanceAmount the mShowBalanceAmount to set
     */
    public void setShowBalanceAmount(boolean mShowBalanceAmount) {
        this.mShowBalanceAmount = mShowBalanceAmount;
    }

    public void setFieldFromTypeCursor() {
        ID = mTypeCursor == TypeCursor.ALLDATA ? QueryAllData.ID : QueryBillDeposits.BDID;
        DATE = mTypeCursor == TypeCursor.ALLDATA ? QueryAllData.Date : QueryBillDeposits.NEXTOCCURRENCEDATE;
        ACCOUNTID = mTypeCursor == TypeCursor.ALLDATA ? QueryAllData.ACCOUNTID : QueryBillDeposits.TOACCOUNTID;
        STATUS = mTypeCursor == TypeCursor.ALLDATA ? QueryAllData.Status : QueryBillDeposits.STATUS;
        AMOUNT = mTypeCursor == TypeCursor.ALLDATA ? QueryAllData.Amount : QueryBillDeposits.AMOUNT;
        PAYEE = mTypeCursor == TypeCursor.ALLDATA ? QueryAllData.Payee : QueryBillDeposits.PAYEENAME;
        TRANSACTIONTYPE = mTypeCursor == TypeCursor.ALLDATA ? QueryAllData.TransactionType : QueryBillDeposits.TRANSCODE;
        CURRENCYID = mTypeCursor == TypeCursor.ALLDATA ? QueryAllData.CURRENCYID : QueryBillDeposits.CURRENCYID;
        TOACCOUNTID = mTypeCursor == TypeCursor.ALLDATA ? QueryAllData.TOACCOUNTID : QueryBillDeposits.TOACCOUNTID;
//        FROMACCOUNTID = mTypeCursor == TypeCursor.ALLDATA ? QueryAllData.FromAccountId : QueryBillDeposits.ACCOUNTID;
        TOAMOUNT = mTypeCursor == TypeCursor.ALLDATA ? QueryAllData.ToAmount : QueryBillDeposits.TOTRANSAMOUNT;
//        FROMAMOUNT = mTypeCursor == TypeCursor.ALLDATA ? QueryAllData.FromAmount : QueryBillDeposits.TRANSAMOUNT;
        TOCURRENCYID = mTypeCursor == TypeCursor.ALLDATA ? QueryAllData.ToCurrencyId : QueryBillDeposits.CURRENCYID;
//        FROMCURRENCYID = mTypeCursor == TypeCursor.ALLDATA ? QueryAllData.FromCurrencyId : QueryBillDeposits.CURRENCYID;
        TOACCOUNTNAME = mTypeCursor == TypeCursor.ALLDATA ? QueryAllData.ToAccountName : QueryBillDeposits.TOACCOUNTNAME;
//        FROMACCOUNTNAME = mTypeCursor == TypeCursor.ALLDATA ? QueryAllData.FromAccountName : QueryBillDeposits.ACCOUNTNAME;
        ACCOUNTNAME = mTypeCursor == TypeCursor.ALLDATA ? QueryAllData.AccountName : QueryBillDeposits.TOACCOUNTNAME;
        CATEGORY = mTypeCursor == TypeCursor.ALLDATA ? QueryAllData.Category : QueryBillDeposits.CATEGNAME;
        SUBCATEGORY = mTypeCursor == TypeCursor.ALLDATA ? QueryAllData.Subcategory : QueryBillDeposits.SUBCATEGNAME;
        NOTES = mTypeCursor == TypeCursor.ALLDATA ? QueryAllData.Notes : QueryBillDeposits.NOTES;
    }

    public void setBalances(HashMap<Integer, BigDecimal> balances) {
        this.balances = balances;

        // update the balances on visible elements.
        for (TextView textView : this.requestingBalanceUpdate) {
            showBalanceAmount(textView);
        }

        this.requestingBalanceUpdate.clear();
    }

    // Private

    private void calculateBalanceAmount(Cursor cursor, AllDataViewHolder holder) {
        try {
            int transId = cursor.getInt(cursor.getColumnIndex(ID));

            String date = cursor.getString(cursor.getColumnIndex(DATE));

            CalculateRunningBalanceTask balanceAmount = new CalculateRunningBalanceTask();
//            CalculateRunningBalanceTask3 balanceAmount = new CalculateRunningBalanceTask3(
//                    getAccountId(), date
//            );
            balanceAmount.setAccountId(getAccountId());
            balanceAmount.setDate(date);
            balanceAmount.setTextView(holder.txtBalance);
            balanceAmount.setContext(mContext);
            balanceAmount.setCurrencyId(getCurrencyId());
            balanceAmount.setTransId(transId);
            // execute thread
            balanceAmount.execute();
        } catch (Exception ex) {
            ExceptionHandler handler = new ExceptionHandler(mContext, this);
            handler.handle(ex, "calculating balance amount");
        }
    }

    /**
     * Display the running balance on account transactions list, or days left on
     * recurring transactions list.
     */
    private void displayBalanceAmountOrDaysLeft(AllDataViewHolder holder, Cursor cursor,
                                                Context context) {
        if (mTypeCursor == TypeCursor.ALLDATA) {
            if (isShowBalanceAmount()) {
                // create thread for calculate balance amount
//                calculateBalanceAmount(cursor, holder);

                // Save transaction Id.
                int txId = cursor.getInt(cursor.getColumnIndex(QueryAllData.ID));
                holder.txtBalance.setTag(txId);

                requestBalanceDisplay(holder.txtBalance);

            } else {
                holder.txtBalance.setVisibility(View.GONE);
            }
        } else {
            int daysLeft = cursor.getInt(cursor.getColumnIndex(QueryBillDeposits.DAYSLEFT));
            if (daysLeft == 0) {
                holder.txtBalance.setText(R.string.due_today);
            } else {
                holder.txtBalance.setText(Integer.toString(Math.abs(daysLeft)) + " " +
                        context.getString(daysLeft > 0 ? R.string.days_remaining : R.string.days_overdue));
            }
            holder.txtBalance.setVisibility(View.VISIBLE);
        }
    }

    /**
     * The most important indicator. Detects whether the values should be from FROM or TO
     * record.
     * @return boolean indicating whether to use *TO values (amountTo)
     */
    private boolean useDestinationValues(boolean isTransfer, Cursor cursor) {
        boolean result;

        if (mTypeCursor.equals(TypeCursor.REPEATINGTRANSACTION)) {
            // Recurring transactions list.
            return false;
        }

        if (isTransfer) {
            // Account transactions lists.

            if (getAccountId() == Constants.NOT_SET) {
                // Search Results
                result = true;
            } else {
                // Account transactions

                // See which value to use.
                if (getAccountId() == cursor.getInt(cursor.getColumnIndex(TOACCOUNTID))) {
                    result = true;
                } else {
                    result = false;
                }
            }
        } else {
            result = false;
        }
        return result;
    }

    private String getPayeeName(Cursor cursor, boolean isTransfer) {
        String result;

        if (isTransfer) {
            // write ToAccountName instead of payee on transfers.
            String accountName;

            if (mTypeCursor.equals(TypeCursor.REPEATINGTRANSACTION)) {
                // Recurring transactions list.
                // Show the destination for the transfer.
                accountName = cursor.getString(cursor.getColumnIndex(ACCOUNTNAME));
            } else {
                // Account transactions list.

                if (mAccountId == Constants.NOT_SET) {
                    // Search results or recurring transactions. Account id is always reset (-1).
                    accountName = cursor.getString(cursor.getColumnIndex(ACCOUNTNAME));
                } else {
                    // Standard checking account. See whether the other account is the source
                    // or the destination of the transfer.
                    int cursorAccountId = cursor.getInt(cursor.getColumnIndex(ACCOUNTID));
                    if (mAccountId != cursorAccountId) {
                        // This is in account transactions list where we display transfers to and from.
                        accountName = cursor.getString(cursor.getColumnIndex(ACCOUNTNAME));
                    } else {
                        // Search results, where we display only incoming transactions.
                        accountName = cursor.getString(cursor.getColumnIndex(TOACCOUNTNAME));
                    }
                }
            }
            if (TextUtils.isEmpty(accountName)) accountName = "-";

            // append square brackets around the account name to distinguish transfers visually.
            accountName = "[%]".replace("%", accountName);
            result = accountName;
        } else {
            // compose payee description
            result = cursor.getString(cursor.getColumnIndex(PAYEE));
        }

        return result;
    }

    private void showBalanceAmount(TextView textView) {
        // get id
        int txId = (int) textView.getTag();
        if (!this.balances.containsKey(txId)) return;

        CurrencyService currencyService = new CurrencyService(mContext);
        BigDecimal currentBalance = this.balances.get(txId);
        String balanceFormatted = currencyService.getCurrencyFormatted(getCurrencyId(),
                currentBalance.doubleValue());
        textView.setText(balanceFormatted);
        textView.setVisibility(View.VISIBLE);
    }

    private void requestBalanceDisplay(TextView textView) {
        // if we have balances, display it immediately.
        if (this.balances != null) {
            showBalanceAmount(textView);
        } else {
            // store for later.
            this.requestingBalanceUpdate.add(textView);
        }
    }
}
