-- PROOF FIXTURE ONLY: synthetic rows booked into scope run_b for account 22214 (not through the consumer).
INSERT INTO positions(accountid,security,updated,quantity,averagecostbasis,projectionscope) VALUES (22214,'MSFT',NOW(),5,100,'run_b');
INSERT INTO trades(id,accountid,created,updated,security,side,quantity,price,state,sourceorderid,projectionscope,clusterepoch,eventidscheme,rundescriptorhash,consensussequence)
  VALUES ('e1-run_b-22214-B',22214,NOW(),NOW(),'MSFT','Buy',5,100,'Processing','run_b-22214','run_b','run_b','epoch-v1',NULL,1);
INSERT INTO orderbook(orderid,accountid,security,side,quantity,remainingquantity,limitprice,status,createdat,updatedat,projectionscope,clusterepoch,eventidscheme)
  VALUES ('run_b-22214',22214,'MSFT','Buy',5,5,99,'NEW',NOW(),NOW(),'run_b','run_b','epoch-v1');
-- PROOF FIXTURE ONLY: synthetic rows booked into scope run_b for account 11413 (not through the consumer).
INSERT INTO positions(accountid,security,updated,quantity,averagecostbasis,projectionscope) VALUES (11413,'NVDA',NOW(),3,100,'run_b');
INSERT INTO trades(id,accountid,created,updated,security,side,quantity,price,state,sourceorderid,projectionscope,clusterepoch,eventidscheme,rundescriptorhash,consensussequence)
  VALUES ('e1-run_b-11413-B',11413,NOW(),NOW(),'NVDA','Buy',3,100,'Processing','run_b-11413','run_b','run_b','epoch-v1',NULL,1);
INSERT INTO orderbook(orderid,accountid,security,side,quantity,remainingquantity,limitprice,status,createdat,updatedat,projectionscope,clusterepoch,eventidscheme)
  VALUES ('run_b-11413',11413,'NVDA','Buy',3,3,99,'NEW',NOW(),NOW(),'run_b','run_b','epoch-v1');
