cd ./benchmarks
for x in "avrora" "luindex" "lusearch" "sunflow"
do
	cd $x
	for y in 1 2 3 4 5
	do 
		./TEST -tool=EFT2 -array=FINE -field=FINE  -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench | grep "#---#" >> ../../results/results_${x}_FT2_1
		./TEST -tool=EFT2S -array=FINE -field=FINE -samplingrate=10 -samplingtype=2  -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench | grep "#---#" >> ../../results/results_${x}_FT2_2
		./TEST -tool=EFT2S -array=FINE -field=FINE -samplingrate=50 -samplingtype=2  -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench | grep "#---#" >> ../../results/results_${x}_FT2_3
		./TEST -tool=EFT2S -array=FINE -field=FINE -samplingscheme=adaptive -samplingtype=0  -burstlen=100 -decRate=10 -minsampling=10 -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench | grep "#---#" >> ../../results/results_${x}_FT2_4
		./TEST -tool=EFT2S -array=FINE -field=FINE -samplingscheme=adaptive -samplingtype=1  -burstlen=200 -decRate=2 -minsampling=20 -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench | grep "#---#" >> ../../results/results_${x}_FT2_5
		./TEST -tool=EFT2S -array=FINE -field=FINE -samplingscheme=adaptive -samplingtype=1  -burstlen=200 -decRate=5 -minsampling=8 -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench | grep "#---#" >> ../../results/results_${x}_FT2_

	done
	cd ../	
done

for x in "xalan"
do
	cd $x
	for y in 1 2 3 4 5
	do 
		./TEST -tool=FT2 -array=FINE -field=FINE  -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench 4 | grep "#---#" >> ../../results/results_${x}_FT2_1
		./TEST -tool=FT2S -array=FINE -field=FINE -samplingrate=10 -samplingtype=2  -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench 4 | grep "#---#" >> ../../results/results_${x}_FT2_2
		./TEST -tool=FT2S -array=FINE -field=FINE -samplingrate=50 -samplingtype=2  -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench 4 | grep "#---#" >> ../../results/results_${x}_FT2_3
		./TEST -tool=FT2S -array=FINE -field=FINE -samplingscheme=adaptive -samplingtype=0  -burstlen=100 -decRate=10 -minsampling=10 -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench 4 | grep "#---#" >> ../../results/results_${x}_FT2_4
		./TEST -tool=FT2S -array=FINE -field=FINE -samplingscheme=adaptive -samplingtype=1  -burstlen=200 -decRate=2 -minsampling=20 -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench 4 | grep "#---#" >> ../../results/results_${x}_FT2_5
		./TEST -tool=FT2S -array=FINE -field=FINE -samplingscheme=adaptive -samplingtype=1  -burstlen=200 -decRate=5 -minsampling=8 -noTidGC -availableProcessors=4 -benchmark=1 -warmup=0 RRBench 4 | grep "#---#" >> ../../results/results_${x}_FT2_6

	done
	cd ../	
done