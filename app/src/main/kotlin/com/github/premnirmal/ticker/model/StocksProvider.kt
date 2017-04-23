package com.github.premnirmal.ticker.model

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.github.premnirmal.ticker.CrashLogger
import com.github.premnirmal.ticker.Injector
import com.github.premnirmal.ticker.RxBus
import com.github.premnirmal.ticker.SimpleSubscriber
import com.github.premnirmal.ticker.Tools
import com.github.premnirmal.ticker.events.ErrorEvent
import com.github.premnirmal.ticker.events.RefreshEvent
import com.github.premnirmal.ticker.network.RobindahoodException
import com.github.premnirmal.ticker.network.StocksApi
import com.github.premnirmal.ticker.network.data.Quote
import com.github.premnirmal.ticker.widget.StockWidget
import org.threeten.bp.DayOfWeek
import org.threeten.bp.Instant
import org.threeten.bp.ZoneId
import org.threeten.bp.ZonedDateTime
import org.threeten.bp.format.TextStyle.SHORT
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import rx.exceptions.CompositeException
import rx.schedulers.Schedulers
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Created by premnirmal on 2/28/16.
 */
@Singleton
class StocksProvider @Inject constructor() : IStocksProvider {

  companion object {

    internal val LAST_FETCHED = "LAST_FETCHED"
    internal val NEXT_FETCH = "NEXT_FETCH"
    internal val POSITION_LIST = "POSITION_LIST"
    internal val DEFAULT_STOCKS = "SPY,DIA,GOOG,AAPL,MSFT"

    val SORTED_STOCK_LIST = "SORTED_STOCK_LIST"
  }

  @Inject internal lateinit var api: StocksApi
  @Inject internal lateinit var context: Context
  @Inject internal lateinit var preferences: SharedPreferences
  @Inject internal lateinit var bus: RxBus

  internal val tickerList: MutableList<String>
  internal val quoteList: MutableList<Quote> = ArrayList()
  internal val positionList: MutableList<Quote>
  internal var lastFetched: Long = 0L
  internal var nextFetch: Long = 0L
  internal val storage: StocksStorage

  init {
    Injector.inject(this)
    storage = StocksStorage()
    val tickerListVars = preferences.getString(SORTED_STOCK_LIST, DEFAULT_STOCKS)
    tickerList = ArrayList(Arrays.asList(
        *tickerListVars.split(",".toRegex()).dropLastWhile(String::isEmpty).toTypedArray()))

    positionList = Tools.stringToPositions(preferences.getString(POSITION_LIST, ""))

    val tickerList = Tools.toCommaSeparatedString(this.tickerList)
    preferences.edit().putString(SORTED_STOCK_LIST, tickerList).apply()
    try {
      lastFetched = preferences.getLong(LAST_FETCHED, 0L)
    } catch (e: Exception) {
      lastFetched = 0L
    }
    nextFetch = preferences.getLong(NEXT_FETCH, 0)
    if (lastFetched == 0L) {
      fetch().subscribe(SimpleSubscriber())
    } else {
      fetchLocal()
    }
  }

  internal fun fetchLocal() {
    synchronized(quoteList, {
      quoteList.clear()
      quoteList.addAll(storage.readStocks())
      if (!quoteList.isEmpty()) {
        sortStockList()
        sendBroadcast()
      } else {
        fetch().subscribe(SimpleSubscriber())
      }
    })
  }

  internal fun save() {
    preferences.edit().putString(POSITION_LIST, Tools.positionsToString(positionList))
        .putString(SORTED_STOCK_LIST, Tools.toCommaSeparatedString(tickerList))
        .putLong(LAST_FETCHED, lastFetched)
        .apply()
    storage.saveStocks(quoteList)
  }

  override fun fetch(): Observable<List<Quote>> {
    return api.getStocks(tickerList)
        .doOnError { t ->
          // why does this happen?
          CrashLogger.logException(RuntimeException("Encountered onError when fetching stocks", t))
          scheduleUpdate(SystemClock.elapsedRealtime() + (3 * 60 * 1000)) // 3 minutes
          if (t is CompositeException) {
            t.exceptions
                .filterIsInstance<RobindahoodException>()
                .forEach { bus.post(ErrorEvent(it.message!!)) }
          }
        }
        .map { stocks ->
          synchronized(quoteList, {
            tickerList.clear()
            stocks.mapTo(tickerList) { it.symbol }
            quoteList.clear()
            quoteList.addAll(stocks)
            lastFetched = api.lastFetched
            save()
          })
          stocks
        }
        .doOnNext { stocks ->
          synchronized(quoteList, {
            sendBroadcast(true)
          })
        }
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
  }

  internal fun sendBroadcast(refresh: Boolean = false) {
    scheduleUpdate(msToNextAlarm, refresh)
  }

  internal val msToNextAlarm: Long
    get() = AlarmScheduler.msOfNextAlarm()

  internal fun scheduleUpdate(msToNextAlarm: Long, refresh: Boolean = false) {
    val widgetManager = AppWidgetManager.getInstance(context)
    val ids = widgetManager.getAppWidgetIds(ComponentName(context, StockWidget::class.java))
    val hasWidget = ids.any { it != AppWidgetManager.INVALID_APPWIDGET_ID }
    if (hasWidget) {
      val updateTime = AlarmScheduler.scheduleUpdate(msToNextAlarm, context)
      nextFetch = updateTime.toInstant().toEpochMilli()
      preferences.edit().putLong(NEXT_FETCH, nextFetch).apply()
    }
    AlarmScheduler.sendBroadcast(context)
    if (refresh) {
      bus.post(RefreshEvent())
    }
  }

  override fun addStock(ticker: String): Collection<String> {
    if (tickerList.contains(ticker)) {
      return tickerList
    }
    tickerList.add(ticker)
    val quote = Quote()
    quote.symbol = ticker
    quoteList.add(quote)
    save()
    fetch().subscribe(SimpleSubscriber())
    return tickerList
  }

  override fun addPosition(ticker: String, shares: Int, price: Float) {
    synchronized(quoteList, {
      var position = getStock(ticker)
      if (position == null) {
        position = Quote()
        position.symbol = ticker
      }
      if (!tickerList.contains(ticker)) {
        tickerList.add(ticker)
      }
      if (shares > 0) {
        position.isPosition = true
        position.positionPrice = price
        position.positionShares = shares
        positionList.remove(position)
        positionList.add(position)
        quoteList.remove(position)
        quoteList.add(position)
        save()
      } else {
        removePosition(ticker)
      }
    })
  }

  override fun removePosition(ticker: String) {
    val position = getStock(ticker) ?: return
    position.isPosition = false
    position.positionPrice = 0f
    position.positionShares = 0
    positionList.remove(position)
    save()
  }

  override fun addStocks(tickers: Collection<String>): Collection<String> {
    tickers
        .filterNot { tickerList.contains(it) }
        .forEach { tickerList.add(it) }
    save()
    fetch().subscribe(SimpleSubscriber())
    return tickerList
  }

  override fun removeStock(ticker: String): Collection<String> {
    synchronized(quoteList, {
      val ticker2 = "^" + ticker // in case it was an index
      tickerList.remove(ticker)
      tickerList.remove(ticker2)
      val dummy = Quote()
      val dummy2 = Quote()
      dummy.symbol = ticker
      dummy2.symbol = ticker2
      quoteList.remove(dummy)
      quoteList.remove(dummy2)
      positionList.remove(dummy)
      positionList.remove(dummy2)
      save()
      scheduleUpdate(msToNextAlarm)
      return tickerList
    })
  }

  override fun getStocks(): Collection<Quote> {
    synchronized(quoteList, {
      sortStockList()

      val newStockList = ArrayList<Quote>()
      var added: Boolean
      // Set all positions
      for (stock in quoteList) {
        added = false
        for (pos in positionList) {
          if (!added && stock.symbol == pos.symbol) {
            stock.isPosition = true
            stock.positionShares = pos.positionShares
            stock.positionPrice = pos.positionPrice
            newStockList.add(stock)
            added = true
          }
        }
        if (!added) {
          newStockList.add(stock)
        }
      }
      return newStockList
    })
  }

  internal fun sortStockList() {
    synchronized(quoteList, {
      if (Tools.autoSortEnabled()) {
        Collections.sort(quoteList)
      } else {
        Collections.sort(quoteList) { lhs, rhs ->
          tickerList.indexOf(lhs.symbol).compareTo(tickerList.indexOf(rhs.symbol))
        }
      }
    })
  }

  override fun rearrange(tickers: List<String>): Collection<Quote> {
    tickerList.clear()
    tickerList.addAll(tickers)
    save()
    sendBroadcast()
    return getStocks()
  }

  override fun getStock(ticker: String): Quote? {
    synchronized(quoteList, {
      val dummy = Quote()
      dummy.symbol = ticker
      val index = quoteList.indexOf(dummy)
      if (index >= 0) {
        val stock = quoteList[index]
        return stock
      } else {
        return null
      }
    })
  }

  override fun getTickers(): List<String> {
    return ArrayList(tickerList)
  }

  override fun lastFetched(): String {
    val fetched: String
    if (lastFetched > 0L) {
      val instant = Instant.ofEpochMilli(lastFetched)
      val time = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault())
      fetched = createTimeString(time)
    } else {
      fetched = ""
    }
    return fetched
  }

  internal fun createTimeString(time: ZonedDateTime): String {
    val fetched: String
    val fetchedDayOfWeek = time.dayOfWeek.value
    val today = ZonedDateTime.now().dayOfWeek.value
    if (today == fetchedDayOfWeek) {
      fetched = Tools.TIME_FORMATTER.format(time)
    } else {
      val day: String = DayOfWeek.from(time).getDisplayName(SHORT, Locale.getDefault())
      val timeStr: String = Tools.TIME_FORMATTER.format(time)
      fetched = "$timeStr $day"
    }
    return fetched
  }

  override fun nextFetch(): String {
    val fetch: String
    if (nextFetch > 0) {
      val instant = Instant.ofEpochMilli(nextFetch)
      val time = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault())
      fetch = createTimeString(time)
    } else {
      fetch = "--"
    }
    return fetch
  }
}