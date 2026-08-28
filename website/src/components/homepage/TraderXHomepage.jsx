import React, {useState} from 'react';
import Head from '@docusaurus/Head';
import Hero from './Hero';
import Results from './Results';
import TabNav from './Tabs';
import ActivePanel from './Sections';
import Footer from './Footer';
import styles from './TraderXHomepage.module.css';

export default function TraderXHomepage() {
  const [activeTab, setActiveTab] = useState('what');

  return (
    <>
      <Head>
        <title>Distributed TraderX | Yeshiva University CS</title>
        <meta
          name="description"
          content="Yeshiva University's build of FINOS TraderX: a sell-side OMS with an LMAX Disruptor matching engine replicated over a three-member Aeron Raft cluster, across seventeen runnable architectural states."
        />
      </Head>
      <div className={styles.page}>
        <Hero />
        <Results />
        <TabNav activeTab={activeTab} onTabChange={setActiveTab} />
        <main className={styles.main}>
          <ActivePanel activeTab={activeTab} />
        </main>
        <Footer />
      </div>
    </>
  );
}
