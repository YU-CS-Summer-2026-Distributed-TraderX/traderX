import { Environment } from './environment.interface';

export const environment: Environment = {
    production:         true,
    accountUrl:         `/account-service`,
    refrenceDataUrl:    `/reference-data`,
    tradesUrl:          `/trade-service/trade/`,
    positionsUrl:       `/position-service`,
    peopleUrl:          `/people-service`,
    orderMatcherUrl:    `/order-matcher`,
    algoUrl:            `/algo`,
    tradeProcessorUrl:  `/trade-processor`,
    tempoUrl:           `/tempo`,
    tradeFeedUrl:       `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/nats-ws`
};
