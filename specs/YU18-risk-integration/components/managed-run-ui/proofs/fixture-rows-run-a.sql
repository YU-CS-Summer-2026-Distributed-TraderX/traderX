-- PROOF FIXTURE ONLY: synthetic rows booked into scope run_a for account 22214 (not through the consumer).
INSERT INTO positions(accountid,security,updated,quantity,averagecostbasis,projectionscope) VALUES (22214,'AAPL',NOW(),10,100,'run_a');
INSERT INTO trades(id,accountid,created,updated,security,side,quantity,price,state,sourceorderid,projectionscope,clusterepoch,eventidscheme,rundescriptorhash,consensussequence)
  VALUES ('e1-run_a-22214-B',22214,NOW(),NOW(),'AAPL','Buy',10,100,'Processing','run_a-22214','run_a','run_a','epoch-v1',NULL,1);
INSERT INTO orderbook(orderid,accountid,security,side,quantity,remainingquantity,limitprice,status,createdat,updatedat,projectionscope,clusterepoch,eventidscheme)
  VALUES ('run_a-22214',22214,'AAPL','Buy',10,10,99,'NEW',NOW(),NOW(),'run_a','run_a','epoch-v1');
-- PROOF FIXTURE ONLY: synthetic rows booked into scope run_a for account 11413 (not through the consumer).
INSERT INTO positions(accountid,security,updated,quantity,averagecostbasis,projectionscope) VALUES (11413,'GOOG',NOW(),7,100,'run_a');
INSERT INTO trades(id,accountid,created,updated,security,side,quantity,price,state,sourceorderid,projectionscope,clusterepoch,eventidscheme,rundescriptorhash,consensussequence)
  VALUES ('e1-run_a-11413-B',11413,NOW(),NOW(),'GOOG','Buy',7,100,'Processing','run_a-11413','run_a','run_a','epoch-v1',NULL,1);
INSERT INTO orderbook(orderid,accountid,security,side,quantity,remainingquantity,limitprice,status,createdat,updatedat,projectionscope,clusterepoch,eventidscheme)
  VALUES ('run_a-11413',11413,'GOOG','Buy',7,7,99,'NEW',NOW(),NOW(),'run_a','run_a','epoch-v1');
