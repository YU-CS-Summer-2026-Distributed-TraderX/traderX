const body = JSON.stringify({
  accountId: Number(process.env.ACCOUNT),
  tickers: process.env.TICKERS,
  price: 150,
});

async function refresh() {
  try {
    const response = await fetch(process.env.REFRESH_URL + "/seed", {
      method: "POST",
      headers: {"content-type": "application/json"},
      body,
    });
    console.error(`[refresh] status=${response.status}`);
  } catch (error) {
    console.error(`[refresh] error=${error.name}`);
  }
}

setInterval(refresh, 8_000);
