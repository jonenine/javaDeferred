# javaDeferred
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;移植jQuery deferred到java，基于java的promise编程模型
			

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;很多语言都支持promise编程模型，像是scala中promise类和jquery（javascript）中的deferred对象等，在java中好像缺少相关实现。笔者不得以自己动手弄了一个。最后选择将jquery中的deferred对象的概念移植到java中来。目前已经应用在企业级项目的高性能服务器和android客户端等项目中。

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Promise编程模型的概念这里也不再赘述，大家自己上网查找即可。这种编程模型主要解决的问题就是“同步调用变异步的问题”，通常解决异步调用的方式是使用“回调”。但普通回调的使用在代码书写，返回值传递和“**异步方法编排上**”非常的不方便。所以才会有Promise模型的诞生。

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;这次会介绍java版的deferred对象的使用方法，以及与jquery版之间的变化和改进。目前开放的版本是基于线程池的版本，正在开发基于akka的版本。在jquery的实现中，因为javascript是单线程的，所以不用考虑线程同步的问题。在java线程池的版的deferred里，基于多线程环境做了很多测试，保证了线程安全及可靠性。

######   1. 基本调用形式

```
final Deferred  def = new Deferred (App. executor);
```

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;执行某个异步调用，比如某个基于网络的异步服务

```
callService(new Response(){
	public void onMessage(Object message){
	def.resolve(message);
}
Public void onFail(Exception e){
	def.reject(e);
}
});
```

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;你可以在构造Deferred 对象后的任意时候，使用def的then方法。比如

```
def.then(new Reply(){
	public Object done(Object d) {
		System.out.println("response:"+d);
		return d;
	}
	public void fail(Object f) {
		System.out.println("error:"+f);
	}
});
```

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;一个经常遇到的场景是callService后将def作为参数传递到其他方法，在其他方法内部再决定def要绑定什么样的后续动作，也就是绑定什么样的then。
    	    注意then方法的定义
    	   
```
public Object done(Object d)
```

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;在实际使用中done通常是以“处理链”的方式来使用的，即你会看到def.then().then().then()…这样的方式，每一个then的done方法接收的参数都是其上一个then的done方法的返回值。通常作为参数传递给某个方法的Deferred上面已经绑定了一些默认的then对象，来处理一些必要的步骤。比如对接收报文的初步解码。
注意同在Reply接口中fail方法是没有返回值的，一旦异步处理链上的某个Deferred被reject，其本身及后面所有的Deferred绑定的then都会被触发fail方法。这保证了整个业务编排上或是你精心设计的算法编排上任意一个环节，无论如何都会得到响应，这也是Promise模型关于异常的最重要的处理方式。

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Promise编程模型本身是强健的，但异步服务却不是总能得到响应。在实际应用中，每一个作为计算或业务环节的Deferred都应该被定时轮询，以保证在异步服务彻底得不到响应的时候（比如你执行了一个数据库查询，但过了很长很长时间仍没有得到回应），可以给Deferred对象reject一个超时错误。

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;响应处理对象then中方法done和fail都是不允许抛出任何异常的，特别是done方法，如果你的算法依赖异常，请在done中加上try…catch，并将异常传换成下一个then可以理解的信息，以便这个Deferred处理链中可以正常执行下去。

######   2. pipe到另外一个异步处理流程上去
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;假如你有如下的业务场景，你需要顺序调用三个异步的webservice服务来得到最终的返回结果，其中没个webservice的入参都和上一个的异步返回结果相关。（注意，异步的webservice是调用之后，服务端立刻返回，服务端处理完成后再主动访问刚才的请求方返回结果的方式）如果将这种webservice调用封装成同步方法无疑在编程上是非常方便的，可以使用我们平常写程序时顺序的书写方式，比如


```
reval1 = callwebservice1(param0)
reval2 = callwebservice2(reval1)
reval3 = callwebservice3(reval2)
```


&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;方便的同时却牺牲了性能。调用线程要在callwebservice方法内阻塞，以等待异步返回。这样的编程方法无法满足高性能及高并发的需要。那么有没有既能类似于平常写程序时顺序的书写方式又能满足异步无阻塞的需要呢，这就是Promise编程模型本身要解决的最大问题。

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;通常解决这种问题的方式是使用pipe，pipe这个方法名称的由来应该是来自于linux shell的管道符，即“|”

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
使用Deferred对象的解决方案类似于如下：


```
Deferred.resolvedDeferred(App.executor,param0).pipe(new AsyncRequest2(){
    public void apply(Object param0,final Deferred newDefered) throws Exception{
        asyncCallwebservice1(param0).onResponse(new Response(){
	    public void onMessage(String message){
		newDefered.resolve(message);
            }
        });
    }
}).pipe(new AsyncRequest2(){
    public void apply(Object reval1,final Deferred newDefered) throws Exception{
        asyncCallwebservice2(reval1).onResponse(new Response(){
	    public void onMessage(String message){
		newDefered.resolve(message);
            }
        });
    }
}).pipe(new AsyncRequest2(){
    public void apply(Object reval2,final Deferred newDefered) throws Exception{
        asyncCallwebservice3(reval3).onResponse(new Response(){
	    public void onMessage(String message){
		newDefered.resolve(message);
            }
        });
    }
}).then(new Reply(){
    public Object done(Object d) {
	/在这里消费最终结果
        return d;
    }
    public void fail(Object f) {
					    
    }
});

```

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;使用Deferred对象提供的方案好处就是，所有的调用都是异步的，上面这一连串代码立刻就会返回。所有的业务编排会按照书写顺序在线程池中的线程里被调用，你也不必担心返回值结果和参数传递过程中的线程安全问题，框架在关键位置都做了同步，也做了相当多的测试用于验证。
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;可以看出，对于异步方法调用而言，比较难以解决的问题是异步算法的编排问题。Deferred对象为异步算法提供了很好的解决方案。
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;相较于AsyncRequest2类还有一个AsyncRequest1类，接口如下：


```
public interface AsyncRequest1<R> {
	public Deferred apply(R result) throws Exception;
}
```


这个类要求在在apply方法中要自己创建Deferred对象。

###### 三. 一些小改进

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;相较于传统promise编程模型，在java多线程环境下做了一些小升级。这里主要介绍synchronize方法 
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Synchronize方法签名如下:

```
Deferred synchronize(ExecutorService executor,Deferred... deferreds)
```

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;实际上，synchronize方法将众多的Deferred对象的完成状态同归集到一个唯一的Deferred对象上去，即如果所有的Deferred对象参数都resolved了，作为最终结果的Deferred也resolve，如果众多的Deferred对象参数有一个reject了，最终的那个Deferred也会立即reject(其他参数的状态都舍弃)。

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;这个方法一般用于多个并行流程最终状态的“归并”中。

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;除了synchronize，框架还提供一些传统promise编程模型没有的改进，比如pipe4fail和source等。

###### 四．在android项目中的应用

   &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;安卓项目经常要分为UI线程和后台worker线程，一个业务经常在UI线程发起，在woker线程执行，最后又返回到UI线程渲染。这是标准的异步算法编写，使用Deferred对象可以很好的简化这部分开发。比如在reply对象的方法中配置元数据，来决定是在UI线程还是worker线程来消费数据。这里只提供一个思路，具体的实现大家自己去摸索吧。
