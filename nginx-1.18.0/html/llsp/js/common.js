// let commonURL = "http://192.168.50.115:8081";
let commonURL = "/api";

// 强制所有 $message 居中（解决 Element UI 默认左对齐问题）
setTimeout(function() {
  if (Vue && Vue.prototype && Vue.prototype.$message) {
    var _msg = Vue.prototype.$message;
    Vue.prototype.$message = function(opts) {
      if (typeof opts === 'string') opts = { message: opts };
      opts.center = true;
      return _msg.call(this, opts);
    };
    // 快捷方法也覆盖 — 通过 type 参数调用原始 _msg，不依赖快捷方法
    ['success','warning','error','info'].forEach(function(t) {
      Vue.prototype.$message[t] = function(msg, opts) {
        if (typeof msg === 'string') {
          return _msg({ message: msg, center: true, type: t });
        }
        return _msg(Object.assign({}, msg || {}, { center: true, type: t }));
      };
    });
  }
}, 100);
// 设置后台服务地址
axios.defaults.baseURL = commonURL;
axios.defaults.timeout = 5000;
// request拦截器，将用户token放入头中
axios.interceptors.request.use(
  config => {
    let token = sessionStorage.getItem("token");
    if(token) config.headers['authorization'] = token
    return config
  },
  error => {
    console.log(error)
    return Promise.reject(error)
  }
)
axios.interceptors.response.use(function (response) {
  // 判断执行结果
  if (!response.data.success) {
    return Promise.reject(response.data.errorMsg)
  }
  return response.data;
}, function (error) {
  // 一般是服务端异常或者网络异常
  console.log(error)
  if(error.response && error.response.status == 401){
    // 未登录，返回提示信息（不自动跳转，允许游客浏览内容）
    return Promise.reject("请先登录");
  }
  return Promise.reject("服务器异常");
});
axios.defaults.paramsSerializer = function(params) {
  let p = "";
  Object.keys(params).forEach(k => {
    if(params[k] != null && params[k] !== ''){
      p = p + "&" + k + "=" + params[k]
    }
  })
  return p;
}
// 检查登录状态，未登录则提示并跳转登录页
function requireLogin() {
  if (!sessionStorage.getItem("token")) {
    if (confirm('请先登录后再操作')) {
      location.href = 'login2.html?redirect=' + encodeURIComponent(location.href);
    }
    return false;
  }
  return true;
}
const util = {
  commonURL,
  getUrlParam(name) {
    let reg = new RegExp("(^|&)" + name + "=([^&]*)(&|$)", "i");
    let r = window.location.search.substr(1).match(reg);
    if (r != null) {
      return decodeURIComponent(r[2]);
    }
    return "";
  },
  formatPrice(val) {
    if (typeof val === 'string') {
      if (isNaN(val)) {
        return null;
      }
      // 价格转为整数
      const index = val.lastIndexOf(".");
      let p = "";
      if (index < 0) {
        // 无小数
        p = val + "00";
      } else if (index === p.length - 2) {
        // 1位小数
        p = val.replace("\.", "") + "0";
      } else {
        // 2位小数
        p = val.replace("\.", "")
      }
      return parseInt(p);
    } else if (typeof val === 'number') {
      if (!val) {
        return null;
      }
      const s = val + '';
      if (s.length === 0) {
        return "0.00";
      }
      if (s.length === 1) {
        return "0.0" + val;
      }
      if (s.length === 2) {
        return "0." + val;
      }
      const i = s.indexOf(".");
      if (i < 0) {
        return s.substring(0, s.length - 2) + "." + s.substring(s.length - 2)
      }
      const num = s.substring(0, i) + s.substring(i + 1);
      if (i === 1) {
        // 1位整数
        return "0.0" + num;
      }
      if (i === 2) {
        return "0." + num;
      }
      if (i > 2) {
        return num.substring(0, i - 2) + "." + num.substring(i - 2)
      }
    }
  }
}
