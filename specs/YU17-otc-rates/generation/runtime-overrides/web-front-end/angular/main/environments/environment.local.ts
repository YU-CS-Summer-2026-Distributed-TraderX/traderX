// State 002 overlay: route browser traffic through edge proxy endpoint.
import { Environment } from './environment.interface';

export const environment: Environment = {
    production:         false,
    accountUrl:         `//${window.location.host}/account-service`,
    refrenceDataUrl:    `//${window.location.host}/reference-data`,
    tradesUrl:          `//${window.location.host}/trade-service/trade/`,
    positionsUrl:       `//${window.location.host}/position-service`,
    peopleUrl:          `//${window.location.host}/people-service`,
    orderMatcherUrl:    `//${window.location.host}/order-matcher`,
    algoUrl:            `//${window.location.host}/algo`,
    tradeProcessorUrl:  `//${window.location.host}/trade-processor`,
    tempoUrl:           `//${window.location.host}/tempo`,
    tradeFeedUrl:       `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/nats-ws`
};
