export const site = {
  name: '远方有温度',
  description: '一个普通人的成长、城市与生活记录。',
  author: '温野',
  email: 'ambition1314@icloud.com',
  qrcode: '/images/qrcode/wechat-qrcode.jpg',
  nav: [
    { href: '/', label: '首页' },
    { href: '/archive', label: '文章归档' },
    { href: '/timeline', label: '成长时间线' },
    { href: '/street', label: '扫街周记' },
    { href: '/cities', label: '城市地图' },
    { href: '/about', label: '关于' }
  ]
};

export const categoryDescriptions: Record<string, string> = {
  关于我: '我向往远方，也珍惜生活里的温度。',
  成长手记: '记录一个普通年轻人从校园、职场、城市到自我重建的过程。',
  北京生活: '北京，是我走进世界的第一站。',
  扫街周记: '用镜头记录城市，也记录那个正在变好的自己。',
  城记远方: '走过一些城市，也走过一些阶段。',
  年终总结: '每年年末，把这一年重新讲一遍。'
};

// 章节入口的展示顺序与中文序号（章节由本地文章按 category 聚合而成）
export const chapterOrder = ['关于我', '成长手记', '年终总结', '北京生活', '扫街周记', '城记远方'] as const;
export const chapterNumerals = ['壹', '贰', '叁', '肆', '伍', '陆'];

// 首屏文案
export const heroCopy = {
  eyebrow: '个人文集 · 自 2021 年 · 写字，拍照，记录成长',
  headline: ['从校园走向城市的这些年，', '慢慢写成了一册档案。'],
  intro:
    '我是温野。写字，拍照，也记录生活。这里收着我从毕业、北京、工作、远方，到一次次做选择时，写下的文章和拍下的街头。',
  note: ['慢慢写，', '慢慢走。']
};

export const timeline = [
  {
    year: '2021',
    title: '毕业，去北京',
    description: '离开校园，开始把自己交给一座更大的城市，也开始学习独立生活。',
    posts: ['Hello 北京!', '毕业季｜是青春飞舞呀', '你好！北京']
  },
  {
    year: '2022',
    title: '入职、成长、摄影开始',
    description: '工作节奏变快，表达欲也更清晰。相机成为重新观察日常的入口。',
    posts: ['我做好了所有准备，站在你面前', '剑未佩妥，出门已是江湖', '扫街周记｜小白入门摄影']
  },
  {
    year: '2023',
    title: '理想生活与城市游走',
    description: '一边工作，一边把生活一点点捡回来。远方不再只是目的地，也是一种状态。',
    posts: ['为理想的生活努力，蛮激动的', '城记｜海定则波宁', '我捡了一路的装备']
  },
  {
    year: '2024',
    title: '做决定，走出来',
    description: '一些决定不一定轻松，但它们让人重新拿回生活的方向盘。',
    posts: ['我想清了所有后果，做了这个决定。', '走出来，走下去']
  },
  {
    year: '2026',
    title: '28岁，继续升级打怪',
    description: '不急着成为谁，先把今天过清楚，把自己照顾好。',
    posts: ['28岁是一种什么样的体验呢？']
  }
];

export const cities = [
  { name: '北京', description: '走进世界的第一站' },
  { name: '宁波', description: '海定则波宁' },
  { name: '青岛', description: '毕业与青春' },
  { name: '西安', description: '过去与远方' },
  { name: '福州', description: '阶段性的停靠' },
  { name: '宁夏', description: '故乡与来处' }
];
