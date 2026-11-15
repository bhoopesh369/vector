importScripts('sports-core.js');
onmessage=({data})=>{try{postMessage({result:SportsCore.analyze(data.input,data.config)});}catch(error){postMessage({error:error.message});}};
