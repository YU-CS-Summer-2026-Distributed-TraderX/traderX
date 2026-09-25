// Reuse the console server for HTTP, WebSocket, authorization and bridge contracts.
// Only loopback services are used by this local launcher.
const target = 'http://127.0.0.1:4321';
export default ['/order-matcher','/position-service','/trade-processor','/account-service','/reference-data','/people-service','/trade-service','/algo','/nats-ws','/auth','/members','/gateways','/mem','/gw','/m0','/m1','/m2','/eod','/risk/demo','/risk/treasury-demo','/cluster','/extracts','/grafana','/prometheus','/tempo','/loki','/taq','/sandbox','/regulatory','/fixorder'].map(context=>({context:[context],target,secure:false,ws:context==='/nats-ws',changeOrigin:true}));
